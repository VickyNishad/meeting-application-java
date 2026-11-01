package com.example.meetplatform.calendar.adapter;

import com.example.meetplatform.calendar.config.CalendarProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Microsoft Graph calendar (Outlook / Microsoft 365). */
@Component
public class MicrosoftCalendarProvider extends OAuthCalendarProvider {

    private static final String GRAPH = "https://graph.microsoft.com/v1.0";
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final CalendarProperties.Provider props;

    public MicrosoftCalendarProvider(CalendarProperties properties, Clock clock) {
        super(clock);
        this.props = properties.microsoft();
    }

    private String tenant() {
        return props.tenant() == null || props.tenant().isBlank() ? "common" : props.tenant();
    }

    @Override
    public String id() {
        return "MICROSOFT";
    }

    @Override
    public boolean configured() {
        return props.configured();
    }

    @Override
    protected String authorizeEndpoint() {
        return "https://login.microsoftonline.com/" + tenant() + "/oauth2/v2.0/authorize";
    }

    @Override
    protected String tokenEndpoint() {
        return "https://login.microsoftonline.com/" + tenant() + "/oauth2/v2.0/token";
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
        return "offline_access openid email User.Read Calendars.ReadWrite";
    }

    @Override
    @SuppressWarnings("unchecked")
    protected String accountEmail(String accessToken) {
        Map<String, Object> me = http.get().uri(GRAPH + "/me").header("Authorization", "Bearer " + accessToken)
                .retrieve().body(Map.class);
        if (me == null) {
            return null;
        }
        Object mail = me.get("mail");
        return mail != null ? (String) mail : (String) me.get("userPrincipalName");
    }

    @Override
    @SuppressWarnings("unchecked")
    public String createEvent(String accessToken, CalendarEvent event) {
        Map<String, Object> body = Map.of(
                "subject", event.title(),
                "body", Map.of("contentType", "text",
                        "content", (event.description() == null ? "" : event.description() + "\n\n") + "Join: " + event.joinUrl()),
                "start", Map.of("dateTime", LOCAL.format(event.start()), "timeZone", "UTC"),
                "end", Map.of("dateTime", LOCAL.format(event.end()), "timeZone", "UTC"),
                "location", Map.of("displayName", event.joinUrl()),
                "attendees", event.attendeeEmails().stream()
                        .map(e -> Map.of("emailAddress", Map.of("address", e), "type", "required")).toList());
        Map<String, Object> created = http.post().uri(GRAPH + "/me/events")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(Map.class);
        return created == null ? null : (String) created.get("id");
    }

    @Override
    public void deleteEvent(String accessToken, String eventId) {
        http.delete().uri(GRAPH + "/me/events/{id}", eventId).header("Authorization", "Bearer " + accessToken)
                .retrieve().toBodilessEntity();
    }
}
