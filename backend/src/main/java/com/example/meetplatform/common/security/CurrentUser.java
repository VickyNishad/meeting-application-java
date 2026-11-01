package com.example.meetplatform.common.security;

import java.util.UUID;

/** The authenticated caller as established from a verified access token. */
public record CurrentUser(UUID userId, String email, boolean systemAdmin, UUID sessionId) {
}
