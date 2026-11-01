package com.example.meetplatform.identity.service;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** NIST SP 800-63B style policy: length over composition rules, plus a block-list of common passwords. */
@Component
public class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 128;

    private static final Set<String> COMMON = Set.of(
            "password1234", "123456789012", "qwertyuiopas", "passwordpassword", "letmein12345",
            "welcome12345", "iloveyou1234", "administrator", "changeme1234", "qwerty123456");

    public void validate(String password, String email, String displayName) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Password must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters.");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "This password is too common.");
        }
        if (email != null && lower.contains(email.toLowerCase(Locale.ROOT).split("@")[0])
                && email.split("@")[0].length() >= 4) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Password must not contain your e-mail name.");
        }
        if (password.chars().distinct().count() < 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Password is too repetitive.");
        }
    }
}
