package com.example.meetplatform.file.controller;

import com.example.meetplatform.common.security.CurrentUserProvider;
import com.example.meetplatform.common.storage.ObjectStorage;
import com.example.meetplatform.file.service.FileService;
import com.example.meetplatform.file.service.FileService.FileView;
import com.example.meetplatform.participant.controller.ParticipantController;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Tag(name = "Files")
@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/files")
public class FileController {

    private final FileService service;
    private final CurrentUserProvider currentUser;

    public FileController(FileService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<FileView> list(@PathVariable UUID meetingId, @RequestParam(required = false) UUID participantId,
                               @RequestHeader(value = ParticipantController.PARTICIPANT_TOKEN_HEADER, required = false)
                               String token) {
        return service.list(meetingId, participantId, token, currentUser.current());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileView upload(@PathVariable UUID meetingId, @RequestPart("file") MultipartFile file,
                           @RequestParam UUID participantId,
                           @RequestHeader(value = ParticipantController.PARTICIPANT_TOKEN_HEADER, required = false)
                           String token) {
        return service.upload(meetingId, participantId, file, token, currentUser.current());
    }

    @PostMapping("/{fileId}/download-url")
    public ObjectStorage.PresignedUrl downloadUrl(@PathVariable UUID meetingId, @PathVariable UUID fileId,
                                                  @RequestParam(required = false) UUID participantId,
                                                  @RequestHeader(value = ParticipantController.PARTICIPANT_TOKEN_HEADER,
                                                          required = false) String token) {
        return service.downloadUrl(meetingId, fileId, participantId, token, currentUser.current());
    }
}
