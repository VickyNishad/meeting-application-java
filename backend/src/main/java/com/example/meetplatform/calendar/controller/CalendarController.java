package com.example.meetplatform.calendar.controller;

import com.example.meetplatform.calendar.service.CalendarConnectionService;
import com.example.meetplatform.calendar.service.CalendarConnectionService.ConnectionView;
import com.example.meetplatform.common.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Calendar integrations")
@RestController
@RequestMapping("/api/v1/calendar")
public class CalendarController {

    private static final Logger log = LoggerFactory.getLogger(CalendarController.class);

    private final CalendarConnectionService service;
    private final CurrentUserProvider currentUser;

    public CalendarController(CalendarConnectionService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/connections")
    public List<ConnectionView> connections() {
        return service.list(currentUser.require());
    }

    /**
     * Returns the provider consent URL; the client navigates the browser there. The response also sets the
     * HttpOnly cookie that binds the OAuth state to this browser (the callback rejects the state without it).
     */
    @PostMapping("/connections/{provider}/authorize")
    public ResponseEntity<Map<String, String>> authorize(@PathVariable String provider) {
        CalendarConnectionService.Authorization authorization = service.authorize(currentUser.require(), provider);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, service.stateCookie(authorization.nonce()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("authorizationUrl", authorization.authorizationUrl()));
    }

    @DeleteMapping("/connections/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable String provider) {
        service.disconnect(currentUser.require(), provider);
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @CookieValue(name = CalendarConnectionService.STATE_COOKIE, required = false)
                                         String browserNonce) {
        boolean ok = false;
        if (error == null && code != null) {
            try {
                service.complete(code, state, browserNonce);
                ok = true;
            } catch (RuntimeException e) {
                log.warn("Calendar connection failed: {}", e.getMessage());
            }
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, service.callbackRedirect(ok))
                .header(HttpHeaders.SET_COOKIE, service.stateCookie(null).toString())
                .build();
    }
}
