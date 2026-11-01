package com.example.meetplatform.meeting.mapper;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.identity.dto.UserDtos.UserSummary;
import com.example.meetplatform.meeting.dto.MeetingDtos.MeetingView;
import com.example.meetplatform.meeting.entity.Meeting;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingRole;
import com.example.meetplatform.meeting.entity.MeetingEnums.MeetingStatus;
import com.example.meetplatform.meeting.service.MeetingRecordingLookup;
import org.springframework.stereotype.Component;

@Component
public class MeetingMapper {

    private final String publicUrl;
    private final MeetingRecordingLookup recordings;

    public MeetingMapper(AppProperties properties, MeetingRecordingLookup recordings) {
        this.publicUrl = properties.publicUrl();
        this.recordings = recordings;
    }

    public String joinUrl(Meeting meeting) {
        return publicUrl + "/j/" + meeting.getJoinCode();
    }

    /** Only a live meeting can be recording; others are not looked up (list pages map many meetings). */
    public boolean recordingInProgress(Meeting meeting) {
        return meeting.getStatus() == MeetingStatus.LIVE && recordings.isRecording(meeting.getId());
    }

    public MeetingView toView(Meeting m, UserSummary host, MeetingRole myRole) {
        return new MeetingView(m.getId(), m.getJoinCode(), joinUrl(m), m.getTitle(), m.getDescription(), m.getType(),
                m.getStatus(), m.getMediaMode(), m.getPrivacy(), m.getOrganizationId(), host, m.getStartTime(),
                m.getEndTime(), m.getTimeZone(), m.getRecurrenceRule(), m.getPasswordHash() != null,
                m.isWaitingRoomEnabled(), m.isAllowGuests(), m.isJoinBeforeHost(), m.isMuteOnEntry(),
                m.getMaxParticipants(), m.getScreenSharePolicy(), m.isChatEnabled(), m.isLocked(), myRole,
                m.getCreatedAt(), m.getVersion() == null ? 0 : m.getVersion(), m.isAutoRecord(),
                recordingInProgress(m), m.getVisibilityMode());
    }
}
