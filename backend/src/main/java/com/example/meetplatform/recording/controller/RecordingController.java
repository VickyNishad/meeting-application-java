package com.example.meetplatform.recording.controller;

import com.example.meetplatform.common.observability.ClientInfo;
import com.example.meetplatform.common.response.PageResponse;
import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.participant.controller.ParticipantController;
import com.example.meetplatform.recording.dto.RecordingDtos.LocalRecordingNotice;
import com.example.meetplatform.recording.dto.RecordingDtos.RecordingView;
import com.example.meetplatform.recording.dto.RecordingDtos.SignedUrl;
import com.example.meetplatform.recording.dto.RecordingDtos.StartRecordingRequest;
import com.example.meetplatform.recording.service.RecordingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Recordings")
@RestController
public class RecordingController {

    private final RecordingService service;
    private final CurrentUserProvider currentUser;

    public RecordingController(RecordingService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/v1/meetings/{meetingId}/recordings")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RecordingView start(@PathVariable UUID meetingId,
                               @Valid @RequestBody(required = false) StartRecordingRequest request) {
        return service.start(meetingId, request == null ? new StartRecordingRequest(null, null) : request,
                currentUser.require());
    }

    @PostMapping("/api/v1/meetings/{meetingId}/recordings/{recordingId}/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RecordingView stop(@PathVariable UUID meetingId, @PathVariable UUID recordingId) {
        return service.stop(meetingId, recordingId, currentUser.require());
    }

    @GetMapping("/api/v1/meetings/{meetingId}/recordings")
    public List<RecordingView> forMeeting(@PathVariable UUID meetingId) {
        return service.forMeeting(meetingId, currentUser.require());
    }

    @PostMapping("/api/v1/meetings/{meetingId}/local-recordings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void localRecording(@PathVariable UUID meetingId, @Valid @RequestBody LocalRecordingNotice notice,
                               @RequestHeader(value = ParticipantController.PARTICIPANT_TOKEN_HEADER, required = false)
                               String token) {
        service.localRecordingNotice(meetingId, notice.participantId(), notice.state(), token, currentUser.current());
    }

    @GetMapping("/api/v1/recordings")
    public PageResponse<RecordingView> library(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return service.library(currentUser.require(), page, size);
    }

    @GetMapping("/api/v1/recordings/{recordingId}")
    public RecordingView get(@PathVariable UUID recordingId) {
        return service.get(recordingId, currentUser.require());
    }

    @PostMapping("/api/v1/recordings/{recordingId}/playback-url")
    public SignedUrl playback(@PathVariable UUID recordingId, HttpServletRequest http) {
        return service.playbackUrl(recordingId, currentUser.require(), ClientInfo.from(http));
    }

    @PostMapping("/api/v1/recordings/{recordingId}/download-url")
    public SignedUrl download(@PathVariable UUID recordingId, HttpServletRequest http) {
        return service.downloadUrl(recordingId, currentUser.require(), ClientInfo.from(http));
    }

    @DeleteMapping("/api/v1/recordings/{recordingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID recordingId, HttpServletRequest http) {
        service.delete(recordingId, currentUser.require(), ClientInfo.from(http));
    }
}
