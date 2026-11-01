package com.example.meetplatform.recording.repository;

import com.example.meetplatform.recording.entity.RecordingAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RecordingAccessLogRepository extends JpaRepository<RecordingAccessLog, UUID> {

    long countByRecordingId(UUID recordingId);
}
