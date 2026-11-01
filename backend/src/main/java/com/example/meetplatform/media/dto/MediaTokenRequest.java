package com.example.meetplatform.media.dto;

import java.time.Duration;

public record MediaTokenRequest(String roomName, String identity, String displayName, String metadata,
                                MediaPermissions permissions, Duration ttl) {
}
