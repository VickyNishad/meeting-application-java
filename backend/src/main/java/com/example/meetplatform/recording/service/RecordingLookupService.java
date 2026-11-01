package com.example.meetplatform.recording.service;

import com.example.meetplatform.meeting.service.MeetingRecordingLookup;
import com.example.meetplatform.recording.entity.Recording;
import com.example.meetplatform.recording.repository.RecordingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RecordingLookupService implements MeetingRecordingLookup {

    // The repository only: RecordingService depends on MeetingService, which (through its mapper) depends on us.
    private final RecordingRepository recordings;

    public RecordingLookupService(RecordingRepository recordings) {
        this.recordings = recordings;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isRecording(UUID meetingId) {
        return recordings.existsByMeetingIdAndStatusIn(meetingId, Recording.IN_FLIGHT);
    }
}
