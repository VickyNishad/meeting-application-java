package com.example.meetplatform.calendar.adapter;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Shared OAuth 2.0 authorization-code plumbing for calendar providers. */
abstract class OAuthCalendarProvider implements CalendarProvider {

    protected final RestClient http;
    protected final Clock clock;

    protected OAuthCalendarProvider(Clock clock) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5));
        requests.setReadTimeout(Duration.ofSeconds(15));
        this.http = RestClient.builder().requestFactory(requests).build();
        this.clock = clock;
    }

    protected abstract String authorizeEndpoint();

    protected abstract String tokenEndpoint();

    protected abstract String clientId();

    protected abstract String clientSecret();

    protected abstract String scopes();

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        return UriComponentsBuilder.fromUriString(authorizeEndpoint())
                .queryParam("client_id", clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", scopes())
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", state)
                .encode().build().toUriString();
    }

    @Override
    public TokenSet exchangeCode(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        return tokenRequest(form);
    }

    @Override
    public TokenSet refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return tokenRequest(form);
    }

    @SuppressWarnings("unchecked")
    private TokenSet tokenRequest(MultiValueMap<String, String> form) {
        form.add("client_id", clientId());
        form.add("client_secret", clientSecret());
        Map<String, Object> body = http.post().uri(tokenEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (body == null || body.get("access_token") == null) {
            throw new IllegalStateException("Token endpoint returned no access token");
        }
        Number expiresIn = (Number) body.getOrDefault("expires_in", 3600);
        return new TokenSet((String) body.get("access_token"), (String) body.get("refresh_token"),
                Instant.now(clock).plusSeconds(expiresIn.longValue() - 60), accountEmail((String) body.get("access_token")),
                (String) body.get("scope"));
    }

    /** Looks up the e-mail of the connected account for display. */
    protected abstract String accountEmail(String accessToken);
}
