package com.example.meetplatform.meeting.service;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import net.fortuna.ical4j.model.Recur;

import java.util.Locale;
import java.util.regex.Pattern;

/** Validates RFC 5545 RRULE values for recurring meetings. */
public final class RecurrenceRules {

    private static final Pattern ALLOWED_FREQ = Pattern.compile("(^|;)FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)(;|$)");

    private RecurrenceRules() {
    }

    /** @return the normalized rule (upper-case, without an "RRULE:" prefix), or null for blank input */
    @SuppressWarnings({"rawtypes", "unused"})
    public static String normalize(String rule) {
        if (rule == null || rule.isBlank()) {
            return null;
        }
        String value = rule.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("RRULE:")) {
            value = value.substring("RRULE:".length());
        }
        if (!ALLOWED_FREQ.matcher(value).find()) {
            throw new ApiException(ErrorCode.INVALID_RECURRENCE, "Recurrence must be DAILY, WEEKLY, MONTHLY or YEARLY.");
        }
        try {
            Recur parsed = new Recur(value);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INVALID_RECURRENCE, "Invalid recurrence rule: " + e.getMessage());
        }
        return value;
    }
}
