package com.example.meetplatform.calendar.adapter;

import com.example.meetplatform.calendar.config.CalendarProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

/** Google Calendar API v3 (primary calendar). */
@Component
public class GoogleCalendarProvider extends OAuthCalendarProvider {

    private static final String API = "https://www.googleapis.com/calendar/v3/calendars/primary/events";

    private final CalendarProperties.Provider props;

    public GoogleCalendarProvider(CalendarProperties properties, Clock clock) {
        super(clock);
        this.props = properties.google();
    }

    @Override
    public String id() {
        return "GOOGLE";
    }

    @Override
    public boolean configured() {
        return props.configured();
    }

    @Override
    protected String authorizeEndpoint() {
        return "https://accounts.google.com/o/oauth2/v2/auth";
    }

    @Override
    protected String tokenEndpoint() {
        return "https://oauth2.googleapis.com/token";
    }

    @Override
    protected String clientId() {
        return props.clientId();
    }

    @Override
    protected String clientSecret() {
        return props.clientSecret();
    }

    @Override
    protected String scopes() {
        return "openid email https://www.googleapis.com/auth/calendar.events";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected String accountEmail(String accessToken) {
        Map<String, Object> info = http.get().uri("https://openidconnect.googleapis.com/v1/userinfo")
                .header("Authorization", "Bearer " + accessToken).retrieve().body(Map.class);
        return info == null ? null : (String) info.get("email");
    }

    @Override
    @SuppressWarnings("unchecked")
    public String createEvent(String accessToken, CalendarEvent event) {
        Map<String, Object> body = Map.of(
                "summary", event.title(),
                "description", (event.description() == null ? "" : event.description() + "\n\n") + "Join: " + event.joinUrl(),
                "location", event.joinUrl(),
                "start", Map.of("dateTime", event.start().toString()),
                "end", Map.of("dateTime", event.end().toString()),
                "attendees", event.attendeeEmails().stream().map(e -> Map.of("email", e)).toList());
        Map<String, Object> created = http.post().uri(API + "?sendUpdates=none")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(Map.class);
        return created == null ? null : (String) created.get("id");
    }

    @Override
    public void deleteEvent(String accessToken, String eventId) {
        http.delete().uri(API + "/{id}", eventId).header("Authorization", "Bearer " + accessToken)
                .retrieve().toBodilessEntity();
    }
}
