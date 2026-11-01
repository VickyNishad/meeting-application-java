package com.example.meetplatform.meeting.entity;

import com.example.meetplatform.common.persistence.AuditedEntity;
import com.example.meetplatform.meeting.entity.MeetingEnums.MediaMode;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingStatus;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingType;
import com.example.meetplatform.meeting.entity.MeetingEnums.Privacy;
import com.example.meetplatform.meeting.entity.MeetingEnums.ScreenSharePolicy;
import com.example.meetplatform.meeting.entity.MeetingEnums.VisibilityMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meetings")
public class Meeting extends AuditedEntity {

    @Column(name = "join_code", nullable = false, length = 16)
    private String joinCode;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 5000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MeetingType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MeetingStatus status = MeetingStatus.SCHEDULED;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_mode", nullable = false, length = 16)
    private MediaMode mediaMode = MediaMode.VIDEO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Privacy privacy = Privacy.LINK;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(name = "recurrence_rule", length = 512)
    private String recurrenceRule;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "waiting_room_enabled", nullable = false)
    private boolean waitingRoomEnabled;

    @Column(name = "allow_guests", nullable = false)
    private boolean allowGuests = true;

    @Column(name = "join_before_host", nullable = false)
    private boolean joinBeforeHost = true;

    @Column(name = "mute_on_entry", nullable = false)
    private boolean muteOnEntry;

    @Column(name = "auto_record", nullable = false)
    private boolean autoRecord;

    @Column(name = "max_participants", nullable = false)
    private int maxParticipants = 100;

    @Enumerated(EnumType.STRING)
    @Column(name = "screen_share_policy", nullable = false, length = 16)
    private ScreenSharePolicy screenSharePolicy = ScreenSharePolicy.EVERYONE;

    @Column(name = "chat_enabled", nullable = false)
    private boolean chatEnabled = true;

    @Column(nullable = false)
    private boolean locked;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility_mode", nullable = false, length = 24)
    private VisibilityMode visibilityMode = VisibilityMode.EVERYONE_VISIBLE;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    protected Meeting() {
    }

    public Meeting(String joinCode, String title, MeetingType type, UUID hostId, String timeZone) {
        this.joinCode = joinCode;
        this.title = title;
        this.type = type;
        this.hostId = hostId;
        this.timeZone = timeZone;
    }

    /** Personal rooms and recurring series return to SCHEDULED after a session; one-off meetings end for good. */
    public boolean isReusable() {
        return type == MeetingType.PERSONAL_ROOM || recurrenceRule != null;
    }

    public boolean isClosed() {
        return status == MeetingStatus.CANCELLED || (status == MeetingStatus.ENDED && !isReusable());
    }

    public void cancel(Instant now, String reason) {
        this.status = MeetingStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancelReason = reason;
    }

    public String getJoinCode() {
        return joinCode;
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

    public MeetingType getType() {
        return type;
    }

    public MeetingStatus getStatus() {
        return status;
    }

    public void setStatus(MeetingStatus status) {
        this.status = status;
    }

    public MediaMode getMediaMode() {
        return mediaMode;
    }

    public void setMediaMode(MediaMode mediaMode) {
        this.mediaMode = mediaMode;
    }

    public Privacy getPrivacy() {
        return privacy;
    }

    public void setPrivacy(Privacy privacy) {
        this.privacy = privacy;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    public UUID getHostId() {
        return hostId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public String getRecurrenceRule() {
        return recurrenceRule;
    }

    public void setRecurrenceRule(String recurrenceRule) {
        this.recurrenceRule = recurrenceRule;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean isWaitingRoomEnabled() {
        return waitingRoomEnabled;
    }

    public void setWaitingRoomEnabled(boolean waitingRoomEnabled) {
        this.waitingRoomEnabled = waitingRoomEnabled;
    }

    public boolean isAllowGuests() {
        return allowGuests;
    }

    public void setAllowGuests(boolean allowGuests) {
        this.allowGuests = allowGuests;
    }

    public boolean isJoinBeforeHost() {
        return joinBeforeHost;
    }

    public void setJoinBeforeHost(boolean joinBeforeHost) {
        this.joinBeforeHost = joinBeforeHost;
    }

    public boolean isMuteOnEntry() {
        return muteOnEntry;
    }

    public void setMuteOnEntry(boolean muteOnEntry) {
        this.muteOnEntry = muteOnEntry;
    }

    public boolean isAutoRecord() {
        return autoRecord;
    }

    public void setAutoRecord(boolean autoRecord) {
        this.autoRecord = autoRecord;
    }

    public int getMaxParticipants() {
        return maxParticipants;
    }

    public void setMaxParticipants(int maxParticipants) {
        this.maxParticipants = maxParticipants;
    }

    public ScreenSharePolicy getScreenSharePolicy() {
        return screenSharePolicy;
    }

    public void setScreenSharePolicy(ScreenSharePolicy screenSharePolicy) {
        this.screenSharePolicy = screenSharePolicy;
    }

    public boolean isChatEnabled() {
        return chatEnabled;
    }

    public void setChatEnabled(boolean chatEnabled) {
        this.chatEnabled = chatEnabled;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public VisibilityMode getVisibilityMode() {
        return visibilityMode;
    }

    public void setVisibilityMode(VisibilityMode visibilityMode) {
        this.visibilityMode = visibilityMode;
    }

    /** Webinar: attendees see and hear only the stage (host and co-hosts). */
    public boolean isStageOnly() {
        return visibilityMode == VisibilityMode.HOST_AND_SELF_ONLY;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }
}
