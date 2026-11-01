package com.example.meetplatform.calendar.service;

import com.example.meetplatform.calendar.adapter.CalendarProvider;
import com.example.meetplatform.calendar.config.CalendarProperties;
import com.example.meetplatform.calendar.entity.CalendarConnection;
import com.example.meetplatform.calendar.repository.CalendarConnectionRepository;
import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.util.Tokens;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Connects, refreshes and disconnects external calendars via OAuth authorization code flow. */
@Service
public class CalendarConnectionService {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    /** Browser-binding nonce for the OAuth state; sent back by the browser only to the callback. */
    public static final String STATE_COOKIE = "mp_calendar_oauth";
    private static final String CALLBACK_PATH = "/api/v1/calendar/oauth/callback";

    private final Map<String, CalendarProvider> providers;
    private final CalendarConnectionRepository connections;
    private final TokenCipher cipher;
    private final CalendarProperties calendarProps;
    private final AppProperties appProps;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNewTx;
    private final Clock clock;

    public CalendarConnectionService(List<CalendarProvider> providers, CalendarConnectionRepository connections,
                                     TokenCipher cipher, CalendarProperties calendarProps, AppProperties appProps,
                                     JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.providers = providers.stream().collect(Collectors.toMap(CalendarProvider::id, Function.identity()));
        this.connections = connections;
        this.cipher = cipher;
        this.calendarProps = calendarProps;
        this.appProps = appProps;
        this.jdbc = jdbc;
        this.requiresNewTx = new TransactionTemplate(tx.getTransactionManager());
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ConnectionView> list(CurrentUser user) {
        Map<String, CalendarConnection> mine = connections.findByUserId(user.userId()).stream()
                .collect(Collectors.toMap(CalendarConnection::getProvider, Function.identity()));
        return providers.values().stream().map(p -> {
            CalendarConnection c = mine.get(p.id());
            return new ConnectionView(p.id(), p.configured() && cipher.available(), c != null,
                    c == null ? null : c.getStatus(), c == null ? null : c.getExternalAccountEmail());
        }).toList();
    }

    /**
     * Starts the consent flow. The state is bound to the initiating browser: it carries the hash of a random
     * nonce that the caller must hand to that browser in the {@link #stateCookie} (HttpOnly, SameSite=Lax,
     * scoped to the callback path), and it is recorded server-side so it can be used only once.
     */
    @Transactional
    public Authorization authorize(CurrentUser user, String providerId) {
        CalendarProvider provider = usable(providerId);
        Instant now = clock.instant();
        Instant expiry = now.plus(STATE_TTL);
        String nonce = Tokens.randomSecret(32);
        String nonceHash = Tokens.sha256(nonce);
        jdbc.update("delete from calendar_oauth_states where expires_at < ?", Timestamp.from(now));
        jdbc.update("insert into calendar_oauth_states (nonce_hash, user_id, provider, expires_at) values (?, ?, ?, ?)",
                nonceHash, user.userId(), provider.id(), Timestamp.from(expiry));
        String state = signState(user.userId() + "." + provider.id() + "." + expiry.getEpochSecond() + "." + nonceHash);
        return new Authorization(provider.authorizationUrl(state, redirectUri()), nonce);
    }

    /**
     * OAuth redirect target. Identity comes from the signed state, which is accepted only together with the
     * nonce cookie of the browser that started the flow, and only once.
     */
    @Transactional
    public void complete(String code, String state, String browserNonce) {
        String payload = verifyState(state);
        String[] parts = payload.split("\\.");
        if (parts.length != 4 || browserNonce == null || browserNonce.isBlank()
                || !MessageDigest.isEqual(Tokens.sha256(browserNonce).getBytes(StandardCharsets.US_ASCII),
                parts[3].getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(ErrorCode.TOKEN_INVALID, "The calendar connection was not started in this browser.");
        }
        UUID userId = UUID.fromString(parts[0]);
        // Consumed in its own committed transaction, so even a failed code exchange cannot be replayed.
        Integer consumed = requiresNewTx.execute(s -> jdbc.update(
                "delete from calendar_oauth_states where nonce_hash = ? and user_id = ? and provider = ? and expires_at > ?",
                parts[3], userId, parts[1], Timestamp.from(clock.instant())));
        if (consumed == null || consumed != 1) {
            throw new ApiException(ErrorCode.TOKEN_INVALID, "This calendar connection request was already used.");
        }
        CalendarProvider provider = usable(parts[1]);
        CalendarProvider.TokenSet tokens = provider.exchangeCode(code, redirectUri());
        CalendarConnection connection = connections.findByUserIdAndProvider(userId, provider.id())
                .orElseGet(() -> new CalendarConnection(userId, provider.id()));
        connection.updateTokens(cipher.encrypt(tokens.accessToken()), cipher.encrypt(tokens.refreshToken()),
                tokens.expiresAt());
        connection.setExternalAccountEmail(tokens.accountEmail());
        connection.setScopes(tokens.scopes());
        connections.save(connection);
    }

    @Transactional
    public void disconnect(CurrentUser user, String providerId) {
        connections.findByUserIdAndProvider(user.userId(), providerId.toUpperCase(Locale.ROOT))
                .ifPresent(connections::delete);
    }

    /** A valid access token for the user's active connection, refreshing it when it is about to expire. */
    @Transactional
    public Optional<ActiveCalendar> activeCalendar(UUID userId) {
        if (!cipher.available()) {
            return Optional.empty();
        }
        return connections.findFirstByUserIdAndStatus(userId, "ACTIVE").flatMap(c -> {
            CalendarProvider provider = providers.get(c.getProvider());
            if (provider == null || !provider.configured()) {
                return Optional.empty();
            }
            String access = cipher.decrypt(c.getAccessTokenEncrypted());
            if (c.getTokenExpiresAt() == null || c.getTokenExpiresAt().isBefore(clock.instant())) {
                try {
                    CalendarProvider.TokenSet refreshed = provider.refresh(cipher.decrypt(c.getRefreshTokenEncrypted()));
                    c.updateTokens(cipher.encrypt(refreshed.accessToken()), cipher.encrypt(refreshed.refreshToken()),
                            refreshed.expiresAt());
                    access = refreshed.accessToken();
                } catch (RuntimeException e) {
                    c.setStatus("ERROR");
                    return Optional.empty();
                }
            }
            return Optional.of(new ActiveCalendar(provider, access));
        });
    }

    public String callbackRedirect(boolean success) {
        return appProps.publicUrl() + "/settings?calendar=" + (success ? "connected" : "error");
    }

    /**
     * The browser-binding cookie: set on the authorize response (same origin as the callback, through the
     * /api proxy) and sent back by the browser only on the provider's top-level redirect to the callback.
     * {@code null} clears it.
     */
    public ResponseCookie stateCookie(String nonce) {
        return ResponseCookie.from(STATE_COOKIE, nonce == null ? "" : nonce)
                .httpOnly(true)
                .secure(appProps.security().cookieSecure())
                .sameSite("Lax")
                .path(CALLBACK_PATH)
                .maxAge(nonce == null ? Duration.ZERO : STATE_TTL)
                .build();
    }

    private CalendarProvider usable(String providerId) {
        CalendarProvider provider = providers.get(providerId.toUpperCase(Locale.ROOT));
        if (provider == null || !provider.configured() || !cipher.available()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "This calendar provider is not configured on the server.");
        }
        return provider;
    }

    private String redirectUri() {
        String base = calendarProps.callbackBaseUrl() == null ? appProps.publicUrl() : calendarProps.callbackBaseUrl();
        return base + CALLBACK_PATH;
    }

    private String signState(String payload) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + hmac(encoded);
    }

    private String verifyState(String state) {
        String[] parts = state == null ? new String[0] : state.split("\\.");
        if (parts.length != 2 || !MessageDigest.isEqual(hmac(parts[0]).getBytes(StandardCharsets.US_ASCII),
                parts[1].getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(ErrorCode.TOKEN_INVALID, "Invalid OAuth state.");
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String[] fields = payload.split("\\.");
        if (fields.length != 4) {
            throw new ApiException(ErrorCode.TOKEN_INVALID, "Invalid OAuth state.");
        }
        long expiry = Long.parseLong(fields[2]);
        if (Instant.ofEpochSecond(expiry).isBefore(clock.instant())) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED, "The calendar connection request expired.");
        }
        return payload;
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(("calendar-state:" + appProps.security().jwtSecret())
                    .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record ConnectionView(String provider, boolean available, boolean connected, String status,
                                 String accountEmail) {
    }

    /** {@code nonce} goes only into the {@link #stateCookie}; never into a URL or a log. */
    public record Authorization(String authorizationUrl, String nonce) {
    }

    public record ActiveCalendar(CalendarProvider provider, String accessToken) {
    }
}
