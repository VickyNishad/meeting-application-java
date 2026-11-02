package com.example.meetplatform.recording.controller;

import com.example.meetplatform.common.response.PageResponse;
import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.participant.controller.ParticipantController;
import com.example.meetplatform.recording.dto.SpeechRecordingDtos.AccessUrl;
import com.example.meetplatform.recording.dto.SpeechRecordingDtos.Facets;
import com.example.meetplatform.recording.dto.SpeechRecordingDtos.FinishRequest;
import com.example.meetplatform.recording.dto.SpeechRecordingDtos.SpeechRecordingView;
import com.example.meetplatform.recording.dto.SpeechRecordingDtos.StartRequest;
import com.example.meetplatform.recording.service.SpeechRecordingService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Speaking clips. Recording endpoints are called by the participant's own browser (guests authenticate with
 * {@code X-Participant-Token}); the dashboard endpoints need a signed-in user and only return what that user may see.
 */
@Tag(name = "Speaking clips")
@RestController
public class SpeechRecordingController {

    private final SpeechRecordingService service;
    private final CurrentUserProvider currentUser;

    public SpeechRecordingController(SpeechRecordingService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/v1/meetings/{meetingId}/speech-recordings")
    @ResponseStatus(HttpStatus.CREATED)
    public SpeechRecordingView start(@PathVariable UUID meetingId, @Valid @RequestBody StartRequest request,
                                     @RequestHeader(value = ParticipantController.PARTICIPANT_TOKEN_HEADER, required = false)
                                     String token) {
        return service.start(meetingId, request, token, currentUser.current());
    }

    /** Body: the raw bytes of chunk {@code seq} (application/octet-stream). */
    @PutMapping(value = "/api/v1/meetings/{meetingId}/speech-recordings/{recordingId}/chunks/{seq}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public SpeechRecordingView chunk(@PathVariable UUID meetingId, @PathVariable UUID recordingId,
                                     @PathVariable int seq, @RequestParam UUID participantId,
                                     @RequestHeader(value = ParticipantController.PARTICIPANT_TOKEN_HEADER, required = false)
                                     String token, HttpServletRequest request) throws IOException {
        return service.appendChunk(meetingId, recordingId, participantId, seq, request.getInputStream(),
                request.getContentLengthLong(), token, currentUser.current());
    }

    @PostMapping("/api/v1/meetings/{meetingId}/speech-recordings/{recordingId}/finish")
    public SpeechRecordingView finish(@PathVariable UUID meetingId, @PathVariable UUID recordingId,
                                      @Valid @RequestBody FinishRequest request,
                                      @RequestHeader(value = ParticipantController.PARTICIPANT_TOKEN_HEADER, required = false)
                                      String token) {
        return service.finish(meetingId, recordingId, request, token, currentUser.current());
    }

    /** {@code view}: "hosted" (meetings you host) or "mine" (your own clips). {@code date}: YYYY-MM-DD. */
    @GetMapping("/api/v1/speech-recordings")
    public PageResponse<SpeechRecordingView> list(@RequestParam(defaultValue = "hosted") String view,
                                                  @RequestParam(required = false)
                                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                  @RequestParam(required = false) UUID meetingId,
                                                  @RequestParam(required = false) UUID participantId,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        return service.list(currentUser.require(), view, date, meetingId, participantId, page, size);
    }

    @GetMapping("/api/v1/speech-recordings/facets")
    public Facets facets(@RequestParam(defaultValue = "hosted") String view) {
        return service.facets(currentUser.require(), view);
    }

    @GetMapping("/api/v1/speech-recordings/{recordingId}")
    public SpeechRecordingView get(@PathVariable UUID recordingId) {
        return service.get(recordingId, currentUser.require());
    }

    @PostMapping("/api/v1/speech-recordings/{recordingId}/access-url")
    public AccessUrl accessUrl(@PathVariable UUID recordingId, @RequestParam(defaultValue = "false") boolean download) {
        return service.accessUrl(recordingId, download, currentUser.require());
    }

    /**
     * The file, through a signed URL from {@link #accessUrl}. Range requests work (seeking in the player). With S3
     * storage the same link redirects to a short-lived presigned URL of the private bucket.
     */
    @Hidden
    @GetMapping(SpeechRecordingService.CONTENT_PATH)
    public ResponseEntity<Resource> content(@PathVariable UUID id, @RequestParam long expires,
                                            @RequestParam(defaultValue = "false") boolean download,
                                            @RequestParam String sig) {
        SpeechRecordingService.ClipFile file = service.content(id, expires, download, sig);
        if (file.redirectUrl() != null) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(file.redirectUrl()))
                    .cacheControl(CacheControl.noStore().cachePrivate())
                    .build();
        }
        ContentDisposition disposition = (file.download() ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file.path()));
    }
}
