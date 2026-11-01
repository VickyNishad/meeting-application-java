package com.example.meetplatform.common.security;

import com.example.meetplatform.common.config.AppProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Issues short-lived API access tokens (HS256). Refresh tokens are opaque and handled by the identity module. */
@Service
public class JwtService {

    public static final String AUDIENCE = "meet-platform-api";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_SYSTEM_ROLE = "sys_role";
    public static final String CLAIM_SESSION = "sid";
    public static final String ROLE_SYSTEM_ADMIN = "SYSTEM_ADMIN";

    private final JwtEncoder encoder;
    private final AppProperties.Security props;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, AppProperties properties, Clock clock) {
        this.encoder = encoder;
        this.props = properties.security();
        this.clock = clock;
    }

    public IssuedAccessToken issue(UUID userId, String email, String systemRole, UUID sessionId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .audience(List.of(AUDIENCE))
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_SYSTEM_ROLE, systemRole)
                .claim(CLAIM_SESSION, sessionId.toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(token, props.accessTokenTtl().toSeconds());
    }

    public record IssuedAccessToken(String value, long expiresInSeconds) {
    }
}
