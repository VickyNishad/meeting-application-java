package com.example.meetplatform.common.security;

import com.example.meetplatform.common.config.AppProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
                                    ProblemResponseWriter problemWriter,
                                    AppProperties properties,
                                    JwtDecoder jwtDecoder,
                                    ObjectProvider<ClientRegistrationRepository> oauthClients,
                                    @Qualifier("oauth2LoginSuccessHandler") ObjectProvider<AuthenticationSuccessHandler> oauthSuccess,
                                    @Qualifier("oauth2LoginFailureHandler") ObjectProvider<AuthenticationFailureHandler> oauthFailure)
            throws Exception {
        http
                // The API authenticates with bearer tokens sent in the Authorization header, which browsers never
                // attach automatically, so classic CSRF tokens are not needed. The only cookie-authenticated
                // endpoints (/auth/refresh, /auth/logout) use a SameSite=Strict cookie scoped to /api/v1/auth and
                // additionally require the X-Requested-With header (checked in AuthController).
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                // Sessions are created only to hold an in-flight OAuth2 authorization request; the security
                // context itself is never stored in the HTTP session.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(c -> c.disable())
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        .contentTypeOptions(c -> {
                        })
                        .referrerPolicy(r -> r.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Metrics reveal internals: only the Prometheus scraper (HTTP Basic, APP_METRICS_SCRAPE_PASSWORD).
                        .requestMatchers("/actuator/prometheus").access(metricsScraper(properties.security()))
                        // Only reachable when APP_OPENAPI_ENABLED=true (off by default).
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout-all").authenticated()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/**").permitAll()
                        // dev profile only (local disk storage): authorized by the URL's HMAC signature, like S3 presigning.
                        .requestMatchers(HttpMethod.GET, "/api/v1/storage/local").permitAll()
                        // Identity is carried by the HMAC-signed OAuth state, bound to the initiating browser by a cookie.
                        .requestMatchers(HttpMethod.GET, "/api/v1/calendar/oauth/callback").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/meetings/by-code/*").permitAll()
                        // Participant-scoped endpoints: guests authenticate with X-Participant-Token, verified in the service.
                        .requestMatchers(HttpMethod.POST, "/api/v1/meetings/*/join", "/api/v1/meetings/*/leave",
                                "/api/v1/meetings/*/local-recordings").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/meetings/*/join-status",
                                "/api/v1/meetings/*/room-state", "/api/v1/meetings/*/audience-access").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/meetings/*/signals/hand",
                                "/api/v1/meetings/*/signals/reaction").permitAll()
                        // Speaking clips recorded by the participant's own browser (guests use X-Participant-Token).
                        .requestMatchers(HttpMethod.POST, "/api/v1/meetings/*/speech-recordings",
                                "/api/v1/meetings/*/speech-recordings/*/finish").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/meetings/*/speech-recordings/*/chunks/*").permitAll()
                        // A clip file through its signed, short-lived URL: the signature is the authorization.
                        .requestMatchers(HttpMethod.GET, "/api/v1/speech-recordings/*/content").permitAll()
                        .requestMatchers("/api/v1/meetings/*/chat", "/api/v1/meetings/*/files",
                                "/api/v1/meetings/*/files/*", "/api/v1/meetings/*/files/*/download-url").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/booking-pages/public/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/bookings").permitAll()
                        .requestMatchers("/api/v1/bookings/manage/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("SYSTEM_ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problemWriter)
                        .accessDeniedHandler(problemWriter))
                .exceptionHandling(e -> e.authenticationEntryPoint(problemWriter).accessDeniedHandler(problemWriter));

        // Social login is enabled only when at least one provider is configured (see application.yml).
        if (oauthClients.getIfAvailable() != null && oauthSuccess.getIfAvailable() != null) {
            http.oauth2Login(o -> {
                o.successHandler(oauthSuccess.getObject());
                AuthenticationFailureHandler failure = oauthFailure.getIfAvailable();
                if (failure != null) {
                    o.failureHandler(failure);
                }
            });
        }
        return http.build();
    }

    @Bean
    SecretKey jwtSigningKey(AppProperties properties) {
        byte[] bytes = properties.security().jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.security.jwt-secret (APP_JWT_SECRET) must be at least "
                    + MIN_SECRET_BYTES + " bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, AppProperties properties, SessionStatusChecker sessions) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(JwtService.AUDIENCE)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
        OAuth2TokenValidator<Jwt> session = jwt -> {
            String sid = jwt.getClaimAsString(JwtService.CLAIM_SESSION);
            boolean active;
            try {
                active = sid != null && sessions.isActive(UUID.fromString(sid));
            } catch (IllegalArgumentException e) {
                active = false;
            }
            return active ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Session revoked", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.security().issuer()), audience, session));
        return decoder;
    }

    /**
     * Allows the request only with {@code Authorization: Basic base64("prometheus:" + password)}. The bearer-token
     * filter ignores Basic credentials, so no user authentication is involved. Without a password: always denied.
     */
    static AuthorizationManager<RequestAuthorizationContext> metricsScraper(AppProperties.Security security) {
        String password = security.metricsScrapePassword();
        byte[] expected = password == null || password.isBlank() ? null
                : ("Basic " + Base64.getEncoder().encodeToString(("prometheus:" + password)
                .getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
        return (authentication, context) -> {
            String header = context.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            return new AuthorizationDecision(expected != null && header != null
                    && MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8)));
        };
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            if (JwtService.ROLE_SYSTEM_ADMIN.equals(jwt.getClaimAsString(JwtService.CLAIM_SYSTEM_ROLE))) {
                authorities.add(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
            }
            return authorities;
        });
        return converter;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "Idempotency-Key",
                "X-Participant-Token"));
        config.setExposedHeaders(List.of("Retry-After", "Location"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
