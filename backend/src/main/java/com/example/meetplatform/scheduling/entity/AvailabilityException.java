package com.example.meetplatform.scheduling.entity;

import com.example.meetplatform.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "availability_exceptions")
public class AvailabilityException extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "exception_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private boolean available;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AvailabilityException() {
    }

    public AvailabilityException(UUID userId, LocalDate date, boolean available, LocalTime startTime, LocalTime endTime,
                                 String reason, Instant createdAt) {
        this.userId = userId;
        this.date = date;
        this.available = available;
        this.startTime = startTime;
        this.endTime = endTime;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getDate() {
        return date;
    }

    public boolean isAvailable() {
        return available;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getReason() {
        return reason;
    }
}
