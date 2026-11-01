package com.example.meetplatform.identity.event;

import java.util.UUID;

/**
 * Domain events published by the identity module. Listeners run synchronously inside the publishing
 * transaction, so an e-mail is only queued if the account change commits.
 */
public final class IdentityEvents {

    private IdentityEvents() {
    }

    /** {@code rawToken} is only carried in memory to build the e-mail link; it is never persisted in clear. */
    public record EmailVerificationRequested(UUID userId, String email, String displayName, String rawToken) {
    }

    public record PasswordResetRequested(UUID userId, String email, String displayName, String rawToken) {
    }

    public record PasswordChanged(UUID userId, String email, String displayName) {
    }
}
