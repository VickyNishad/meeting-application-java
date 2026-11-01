package com.example.meetplatform.calendar.adapter;

import java.time.Instant;
import java.util.List;

/** Port to an external calendar (Google Calendar, Microsoft 365 / Outlook). */
public interface CalendarProvider {

    String id();

    boolean configured();

    String authorizationUrl(String state, String redirectUri);

    TokenSet exchangeCode(String code, String redirectUri);

    TokenSet refresh(String refreshToken);

    /** @return the provider's event id */
    String createEvent(String accessToken, CalendarEvent event);

    void deleteEvent(String accessToken, String eventId);

    record TokenSet(String accessToken, String refreshToken, Instant expiresAt, String accountEmail, String scopes) {
    }

    record CalendarEvent(String title, String description, Instant start, Instant end, String joinUrl,
                         List<String> attendeeEmails) {
    }
}
