package com.example.meetplatform.organization.entity;

import com.example.meetplatform.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "organizations")
public class Organization extends AuditedEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 80)
    private String slug;

    @Column(name = "default_recording_retention_days", nullable = false)
    private int defaultRecordingRetentionDays = 90;

    @Column(name = "allow_guests", nullable = false)
    private boolean allowGuests = true;

    protected Organization() {
    }

    public Organization(String name, String slug) {
        this.name = name;
        this.slug = slug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public int getDefaultRecordingRetentionDays() {
        return defaultRecordingRetentionDays;
    }

    public void setDefaultRecordingRetentionDays(int days) {
        this.defaultRecordingRetentionDays = days;
    }

    public boolean isAllowGuests() {
        return allowGuests;
    }

    public void setAllowGuests(boolean allowGuests) {
        this.allowGuests = allowGuests;
    }
}
