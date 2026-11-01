package com.example.meetplatform.booking.entity;

import com.example.meetplatform.common.persistence.AuditedEntity;
import com.example.meetplatform.meeting.entity.MeetingEnums.MediaMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "booking_pages")
public class BookingPage extends AuditedEntity {

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 60)
    private String slug;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 5000)
    private String description;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "slot_interval_minutes", nullable = false)
    private int slotIntervalMinutes;

    @Column(name = "min_notice_minutes", nullable = false)
    private int minNoticeMinutes = 240;

    @Column(name = "max_advance_days", nullable = false)
    private int maxAdvanceDays = 60;

    @Column(name = "buffer_before_minutes", nullable = false)
    private int bufferBeforeMinutes;

    @Column(name = "buffer_after_minutes", nullable = false)
    private int bufferAfterMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_mode", nullable = false, length = 16)
    private MediaMode mediaMode = MediaMode.VIDEO;

    @Column(nullable = false)
    private boolean active = true;

    protected BookingPage() {
    }

    public BookingPage(UUID ownerId, String slug, String title, int durationMinutes) {
        this.ownerId = ownerId;
        this.slug = slug;
        this.title = title;
        this.durationMinutes = durationMinutes;
        this.slotIntervalMinutes = durationMinutes;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public int getSlotIntervalMinutes() {
        return slotIntervalMinutes;
    }

    public void setSlotIntervalMinutes(int slotIntervalMinutes) {
        this.slotIntervalMinutes = slotIntervalMinutes;
    }

    public int getMinNoticeMinutes() {
        return minNoticeMinutes;
    }

    public void setMinNoticeMinutes(int minNoticeMinutes) {
        this.minNoticeMinutes = minNoticeMinutes;
    }

    public int getMaxAdvanceDays() {
        return maxAdvanceDays;
    }

    public void setMaxAdvanceDays(int maxAdvanceDays) {
        this.maxAdvanceDays = maxAdvanceDays;
    }

    public int getBufferBeforeMinutes() {
        return bufferBeforeMinutes;
    }

    public void setBufferBeforeMinutes(int bufferBeforeMinutes) {
        this.bufferBeforeMinutes = bufferBeforeMinutes;
    }

    public int getBufferAfterMinutes() {
        return bufferAfterMinutes;
    }

    public void setBufferAfterMinutes(int bufferAfterMinutes) {
        this.bufferAfterMinutes = bufferAfterMinutes;
    }

    public MediaMode getMediaMode() {
        return mediaMode;
    }

    public void setMediaMode(MediaMode mediaMode) {
        this.mediaMode = mediaMode;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
