package com.example.meetplatform.booking.event;

import java.util.UUID;

public final class BookingEvents {

    private BookingEvents() {
    }

    public record BookingConfirmed(UUID bookingId) {
    }

    public record BookingCancelled(UUID bookingId, boolean byHost) {
    }

    public record BookingRescheduled(UUID previousBookingId, UUID bookingId) {
    }
}
