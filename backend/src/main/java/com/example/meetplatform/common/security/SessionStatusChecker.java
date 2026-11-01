package com.example.meetplatform.common.security;

import java.util.UUID;

/**
 * Lets the token validator reject access tokens whose session was revoked (logout, password change,
 * account disabled) before the token's natural expiry. Implemented by the identity module.
 */
public interface SessionStatusChecker {

    boolean isActive(UUID sessionId);
}
