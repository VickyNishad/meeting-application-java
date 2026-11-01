package com.example.meetplatform.common.security;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class CurrentUserProvider {

    public Optional<CurrentUser> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            Jwt jwt = token.getToken();
            String sid = jwt.getClaimAsString(JwtService.CLAIM_SESSION);
            return Optional.of(new CurrentUser(
                    UUID.fromString(jwt.getSubject()),
                    jwt.getClaimAsString(JwtService.CLAIM_EMAIL),
                    JwtService.ROLE_SYSTEM_ADMIN.equals(jwt.getClaimAsString(JwtService.CLAIM_SYSTEM_ROLE)),
                    sid == null ? null : UUID.fromString(sid)));
        }
        return Optional.empty();
    }

    public CurrentUser require() {
        return current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required."));
    }
}
