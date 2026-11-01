package com.example.meetplatform.media.webhook;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Webhooks")
@RestController
@RequestMapping("/api/v1/webhooks")
public class LiveKitWebhookController {

    private final MediaWebhookService service;

    public LiveKitWebhookController(MediaWebhookService service) {
        this.service = service;
    }

    /** The raw body is required: the signature covers its exact bytes. */
    @PostMapping(value = "/livekit", consumes = {"application/webhook+json", "application/json"})
    public ResponseEntity<Void> livekit(@RequestBody String body,
                                        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        service.handle(body, auth);
        return ResponseEntity.ok().build();
    }
}
