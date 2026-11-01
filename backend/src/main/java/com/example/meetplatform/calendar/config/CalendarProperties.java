package com.example.meetplatform.calendar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional calendar-provider credentials. A provider is offered only when its client id and secret are set;
 * token storage additionally requires {@code encryptionKey} (32 random bytes, Base64).
 */
@ConfigurationProperties(prefix = "app.calendar")
public record CalendarProperties(String encryptionKey, String callbackBaseUrl, Provider google, Provider microsoft) {

    public record Provider(String clientId, String clientSecret, String tenant) {

        public boolean configured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    public Provider google() {
        return google == null ? new Provider(null, null, null) : google;
    }

    public Provider microsoft() {
        return microsoft == null ? new Provider(null, null, null) : microsoft;
    }
}
