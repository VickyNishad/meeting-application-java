package com.example.meetplatform.scheduling.entity;

import com.example.meetplatform.common.persistence.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** The time zone in which a user's availability rules are expressed. Id = user id. */
@Entity
@Table(name = "availability_schedules")
@AttributeOverride(name = "id", column = @Column(name = "user_id"))
public class AvailabilitySchedule extends BaseEntity {

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected AvailabilitySchedule() {
    }

    public AvailabilitySchedule(UUID userId, String timeZone) {
        super(userId);
        this.timeZone = timeZone;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public Long getVersion() {
        return version;
    }
}
