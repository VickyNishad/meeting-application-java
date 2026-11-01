package com.example.meetplatform.identity.service;

import com.example.meetplatform.common.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/** The browser refresh-token cookie: HttpOnly, SameSite=Strict and scoped to the auth endpoints only. */
@Component
public class RefreshCookies {

    public static final String NAME = "mp_refresh";
    private static final String PATH = "/api/v1/auth";

    private final AppProperties.Security props;

    public RefreshCookies(AppProperties properties) {
        this.props = properties.security();
    }

    public ResponseCookie issue(String refreshToken) {
        return base(refreshToken).maxAge(props.refreshTokenTtl()).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    public Optional<String> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> NAME.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank())
                .map(jakarta.servlet.http.Cookie::getValue)
                .findFirst();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Strict")
                .path(PATH);
    }
}
