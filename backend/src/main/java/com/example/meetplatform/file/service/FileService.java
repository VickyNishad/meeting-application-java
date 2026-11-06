package com.example.meetplatform.file.service;

import com.example.meetplatform.common.config.AppProperties;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.common.security.RateLimiter;
import com.example.meetplatform.common.storage.ObjectStorage;
import com.example.meetplatform.media.service.RoomSignals;
import com.example.meetplatform.meeting.repository.MeetingRepository;
import com.example.meetplatform.meeting.service.MeetingPermissions;
import com.example.meetplatform.participant.entity.MeetingParticipant;
import com.example.meetplatform.participant.service.ParticipantAccessService;
import com.example.meetplatform.room.service.RoomSessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.meetplatform.file.entity.FileMetadata;
import com.example.meetplatform.file.repository.FileMetadataRepository;

/**
 * In-meeting file sharing. Files go to private object storage under a server-generated key and are only
 * reachable through short-lived signed URLs; type is checked against an allow-list by declared content type,
 * extension and (where the type has one) the file signature, and executable types are never accepted.
 */
@Service
public class FileService {

    private static final Map<String, Set<String>> EXTENSIONS = Map.ofEntries(
            Map.entry("application/pdf", Set.of("pdf")),
            Map.entry("image/png", Set.of("png")),
            Map.entry("image/jpeg", Set.of("jpg", "jpeg")),
            Map.entry("image/gif", Set.of("gif")),
            Map.entry("image/webp", Set.of("webp")),
            Map.entry("text/plain", Set.of("txt", "md", "log")),
            Map.entry("text/csv", Set.of("csv")),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of("docx")),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Set.of("xlsx")),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", Set.of("pptx")),
            Map.entry("application/zip", Set.of("zip")));

    private static final int SIGNATURE_BYTES = 16;
    private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
    private static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF);
    private static final byte[] GIF87A = "GIF87a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GIF89A = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP = "WEBP".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP = bytes('P', 'K', 0x03, 0x04);
    private static final byte[] ZIP_EMPTY = bytes('P', 'K', 0x05, 0x06);

    private final FileMetadataRepository files;
    private final ParticipantAccessService participantAccess;
    private final MeetingPermissions permissions;
    private final RoomSessionService roomSessions;
    private final RoomSignals signals;
    private final ObjectStorage storage;
    private final RateLimiter rateLimiter;
    private final AppProperties properties;
    private final Set<String> allowedTypes;
    private final MeetingRepository meetings;
    private final Clock clock;

    public FileService(FileMetadataRepository files, ParticipantAccessService participantAccess,
                       MeetingPermissions permissions, RoomSessionService roomSessions, RoomSignals signals,
                       ObjectStorage storage, RateLimiter rateLimiter, AppProperties properties,
                       MeetingRepository meetings, Clock clock) {
        this.files = files;
        this.meetings = meetings;
        this.participantAccess = participantAccess;
        this.permissions = permissions;
        this.roomSessions = roomSessions;
        this.signals = signals;
        this.storage = storage;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.allowedTypes = properties.files().allowedContentTypes().stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.clock = clock;
    }

    @Transactional
    public FileView upload(UUID meetingId, UUID participantId, MultipartFile file, String token,
                           Optional<CurrentUser> user) {
        MeetingParticipant uploader = participantAccess.resolvePresent(meetingId, participantId, token, user);
        if (!permissions.has(uploader.getRole(), MeetingPermissions.FILE_SHARE)) {
            throw ApiException.forbidden("File sharing is not available to guests.");
        }
        // Shared files list their uploader for everyone: in a webinar only the stage shares files.
        if (!uploader.getRole().isModerator() && meetings.findById(meetingId).map(m -> m.isStageOnly()).orElse(false)) {
            throw ApiException.forbidden("In this meeting only the host and co-hosts can share files.");
        }
        rateLimiter.check("file-upload", participantId.toString(), 20, Duration.ofMinutes(10));
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A non-empty file is required.");
        }
        if (file.getSize() > properties.files().maxSizeBytes()) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE, "Files are limited to "
                    + properties.files().maxSizeBytes() / (1024 * 1024) + " MB.");
        }
        String fileName = sanitize(file.getOriginalFilename());
        String contentType = validateType(file.getContentType(), fileName);
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(SIGNATURE_BYTES);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "The file could not be read.", e);
        }
        if (!contentMatches(contentType, head)) {
            throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED,
                    "The file content does not match its type (" + contentType + ").");
        }

        UUID fileId = UUID.randomUUID();
        String key = "files/" + meetingId + "/" + fileId;
        String sha256;
        try (InputStream raw = file.getInputStream();
             DigestInputStream in = new DigestInputStream(raw, MessageDigest.getInstance("SHA-256"))) {
            storage.put(properties.storage().filesBucket(), key, in, file.getSize(), contentType);
            sha256 = HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "The file could not be stored.", e);
        }
        FileMetadata saved = files.save(new FileMetadata(fileId, meetingId, uploader.getId(), uploader.getDisplayName(),
                fileName, contentType, file.getSize(), sha256, key, clock.instant()));
        FileView view = FileView.of(saved);
        roomSessions.active(meetingId).ifPresent(room -> signals.broadcast(room.getLivekitRoomName(),
                RoomSignals.TOPIC_FILES, Map.of("type", "file", "file", view)));
        return view;
    }

    @Transactional(readOnly = true)
    public List<FileView> list(UUID meetingId, UUID participantId, String token, Optional<CurrentUser> user) {
        participantAccess.requireMeetingViewer(meetingId, participantId, token, user);
        return files.findByMeetingIdAndDeletedAtIsNullOrderByCreatedAtDesc(meetingId).stream().map(FileView::of).toList();
    }

    @Transactional(readOnly = true)
    public ObjectStorage.PresignedUrl downloadUrl(UUID meetingId, UUID fileId, UUID participantId, String token,
                                                  Optional<CurrentUser> user) {
        participantAccess.requireMeetingViewer(meetingId, participantId, token, user);
        FileMetadata meta = files.findByIdAndMeetingIdAndDeletedAtIsNull(fileId, meetingId)
                .orElseThrow(() -> ApiException.notFound("File"));
        // In the database, but its object may be gone: say so now instead of handing out a dead link.
        if (!storage.exists(properties.storage().filesBucket(), meta.getStorageKey())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "This file is no longer available.");
        }
        return storage.presignGet(properties.storage().filesBucket(), meta.getStorageKey(),
                properties.storage().presignTtl(), meta.getFileName());
    }

    /**
     * Whoever shared the file, even after leaving or after the meeting ended, or a host or co-host who is in the
     * meeting now. Nobody who was denied or removed can delete, not even their own file. The row is marked in the same
     * transaction as the
     * object is removed: if storage fails, the row stays and the file is still listed. Shared files are only ever
     * removed this way, never automatically.
     */
    @Transactional
    public void delete(UUID meetingId, UUID fileId, UUID participantId, String token, Optional<CurrentUser> user) {
        MeetingParticipant caller = participantAccess.resolve(meetingId, participantId, token, user);
        if (!participantAccess.inGoodStanding(caller)) {
            throw ApiException.forbidden("You no longer have access to this meeting's files.");
        }
        FileMetadata meta = files.findByIdAndMeetingIdAndDeletedAtIsNull(fileId, meetingId)
                .orElseThrow(() -> ApiException.notFound("File"));
        boolean uploader = caller.getId().equals(meta.getUploadedByParticipantId());
        boolean activeModerator = caller.isPresent() && caller.getRole().isModerator();
        if (!uploader && !activeModerator) {
            throw ApiException.forbidden("Only the person who shared this file, or the host, can delete it.");
        }
        storage.delete(properties.storage().filesBucket(), meta.getStorageKey());
        meta.markDeleted(clock.instant());
        roomSessions.active(meetingId).ifPresent(room -> signals.broadcast(room.getLivekitRoomName(),
                RoomSignals.TOPIC_FILES, Map.of("type", "file-deleted", "fileId", fileId.toString())));
    }

    private String validateType(String declared, String fileName) {
        String type = declared == null ? "" : declared.split(";")[0].trim().toLowerCase(Locale.ROOT);
        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        Set<String> expected = EXTENSIONS.get(type);
        if (!allowedTypes.contains(type) || expected == null || !expected.contains(extension)) {
            throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED,
                    "This file type is not allowed. Allowed: PDF, images, text/CSV, Office documents, ZIP.");
        }
        return type;
    }

    /**
     * Magic-byte check: the declared type and extension come from the client, so types with a well-known
     * signature must also start with it (a renamed executable or HTML page is rejected). Plain text and CSV have
     * no signature and are only checked by type and extension.
     */
    static boolean contentMatches(String contentType, byte[] head) {
        return switch (contentType) {
            case "application/pdf" -> startsWith(head, 0, PDF);
            case "image/png" -> startsWith(head, 0, PNG);
            case "image/jpeg" -> startsWith(head, 0, JPEG);
            case "image/gif" -> startsWith(head, 0, GIF87A) || startsWith(head, 0, GIF89A);
            case "image/webp" -> startsWith(head, 0, RIFF) && startsWith(head, 8, WEBP);
            // Office Open XML documents are ZIP containers.
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                 "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                 "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> startsWith(head, 0, ZIP);
            case "application/zip" -> startsWith(head, 0, ZIP) || startsWith(head, 0, ZIP_EMPTY);
            default -> true;
        };
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (data.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    static String sanitize(String original) {
        String name = original == null ? "file" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}\"<>|:*?]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "file";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    public record FileView(UUID id, String fileName, String contentType, long sizeBytes, String uploadedBy,
                           UUID uploadedByParticipantId,
                           Instant createdAt) {

        static FileView of(FileMetadata m) {
            return new FileView(m.getId(), m.getFileName(), m.getContentType(), m.getSizeBytes(), m.getUploadedByName(),
                    m.getUploadedByParticipantId(),
                    m.getCreatedAt());
        }
    }
}
