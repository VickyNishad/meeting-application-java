package com.example.meetplatform.common.validation;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;

import java.time.ZoneId;

public final class TimeZones {

    private TimeZones() {
    }

    public static boolean isValid(String value) {
        return value != null && (ZoneId.getAvailableZoneIds().contains(value) || "UTC".equals(value));
    }

    public static ZoneId require(String value) {
        if (!isValid(value)) {
            throw new ApiException(ErrorCode.INVALID_TIME_ZONE, "Unknown time zone: " + value);
        }
        return ZoneId.of(value);
    }
}
