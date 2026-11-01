package com.example.meetplatform.file.repository;

import com.example.meetplatform.file.entity.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {

    List<FileMetadata> findByMeetingIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID meetingId);

    Optional<FileMetadata> findByIdAndMeetingIdAndDeletedAtIsNull(UUID id, UUID meetingId);
}
