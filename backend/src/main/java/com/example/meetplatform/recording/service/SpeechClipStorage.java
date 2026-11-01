package com.example.meetplatform.recording.service;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The folder tree of speaking clips on disk, under {@code RECORDING_STORAGE_PATH}:
 * {@code DD-MM-YYYY/<Meeting_Title>/<Participant_Name>/DDMMYYHHmmss_<id>.webm}.
 *
 * <p>Every path is built here from server-side data (dates, ids, sanitized names); nothing a client sends is ever
 * used as a path. Every resolved path is checked to stay inside the root. A clip is written to {@code <name>.part}
 * while it is recorded and becomes {@code <name>} only once finalized and verified; an existing file is never
 * replaced.
 */
@Component
public class SpeechClipStorage {

    private static final Logger log = LoggerFactory.getLogger(SpeechClipStorage.class);

    static final String PART_SUFFIX = ".part";
    private static final DateTimeFormatter FOLDER_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("ddMMyyHHmmss");
    /** WebM / Matroska files start with the EBML magic number. */
    private static final byte[] EBML_MAGIC = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};
    private static final int MAX_NAME_LENGTH = 60;
    // Letters, combining marks (vowel signs of Indic scripts, accents) and digits of any script.
    private static final Pattern NOT_ALLOWED = Pattern.compile("[^\\p{L}\\p{M}\\p{N}_-]");
    private static final Pattern REPEATED = Pattern.compile("_+");
    /** Names Windows refuses as file or folder names, whatever the extension. */
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private final Path root;
    private final long minFreeBytes;

    public SpeechClipStorage(@Value("${app.speech-recordings.storage-path}") String storagePath,
                             @Value("${app.speech-recordings.min-free-bytes:209715200}") long minFreeBytes) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalStateException("app.speech-recordings.storage-path (RECORDING_STORAGE_PATH) must be set");
        }
        this.root = Path.of(storagePath).toAbsolutePath().normalize();
        this.minFreeBytes = minFreeBytes;
        try {
            Files.createDirectories(root);
            log.info("Speaking clips are stored under {}", root);
        } catch (IOException e) {
            // Not fatal at startup: every write reports STORAGE_UNAVAILABLE until the folder can be created.
            log.error("Cannot create the recording folder {}: {}", root, e.getMessage());
        }
    }

    /**
     * A safe single folder name: letters and digits of any script, '-' and '_' (spaces become '_'); no dots, slashes
     * or other separators, so it can never climb out of its parent; not a reserved Windows name; at most 60 characters
     * (Unicode code points: a character outside the Basic Multilingual Plane is never cut in half).
     */
    public static String sanitize(String name, String fallback) {
        String s = name == null ? "" : Normalizer.normalize(name, Normalizer.Form.NFKC).strip();
        s = s.replaceAll("\\s+", "_");
        s = NOT_ALLOWED.matcher(s).replaceAll("");
        s = REPEATED.matcher(s).replaceAll("_");
        s = s.replaceAll("^[_-]+|[_-]+$", "");
        if (s.codePointCount(0, s.length()) > MAX_NAME_LENGTH) {
            s = s.substring(0, s.offsetByCodePoints(0, MAX_NAME_LENGTH)).replaceAll("[_-]+$", "");
        }
        if (s.isEmpty()) {
            return fallback;
        }
        if (RESERVED.contains(s.toUpperCase(Locale.ROOT))) {
            return "_" + s;
        }
        return s;
    }

    /** {@code DD-MM-YYYY/<Meeting_Title>/<Participant_Name>/DDMMYYHHmmss_<uniqueId>.webm}, dates in {@code zone}. */
    public String relativePath(Instant start, ZoneId zone, String meetingTitle, String participantName, String uniqueId) {
        return FOLDER_DATE.format(start.atZone(zone)) + "/" + sanitize(meetingTitle, "Meeting") + "/"
                + sanitize(participantName, "Participant") + "/" + fileName(start, zone, uniqueId);
    }

    public static String fileName(Instant start, ZoneId zone, String uniqueId) {
        return FILE_STAMP.format(start.atZone(zone)) + "_" + uniqueId + ".webm";
    }

    /** The absolute path of a stored relative path; refuses anything that would leave the root. */
    Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw ApiException.notFound("Recording file");
        }
        Path p;
        try {
            p = root.resolve(relativePath).normalize();
        } catch (InvalidPathException e) {
            // Not a valid path on this file system (e.g. a stored name the database could not keep as written).
            throw ApiException.notFound("Recording file");
        }
        if (!p.startsWith(root) || p.equals(root)) {
            throw ApiException.notFound("Recording file");
        }
        return p;
    }

    /** Whether the final file or a partial one is already there (a new clip must not take that name). */
    public boolean exists(String relativePath) {
        Path p = resolve(relativePath);
        return Files.exists(p) || Files.exists(part(p));
    }

    /** Refuses new recording data when the disk holding the root is (nearly) full. */
    public void requireFreeSpace() {
        long usable;
        try {
            Files.createDirectories(root);
            usable = Files.getFileStore(root).getUsableSpace();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "Recording storage is unavailable.", e);
        }
        if (usable < minFreeBytes) {
            throw new ApiException(ErrorCode.INSUFFICIENT_STORAGE, "The recording disk is full.");
        }
    }

    /**
     * Writes one chunk into the clip's partial file (created, with its folders, on the first chunk) at
     * {@code storedSize}, the size the database has recorded for the clip, and returns the new size. Bytes beyond that
     * size (a chunk written before its transaction was rolled back, or a write cut off by a crash) are discarded
     * first, so a retried chunk is never stored twice. Refuses a chunk that would make the clip larger than
     * {@code maxClipBytes}.
     */
    public long append(String relativePath, InputStream chunk, long storedSize, long maxClipBytes) {
        requireFreeSpace();
        Path part = part(resolve(relativePath));
        try {
            Files.createDirectories(part.getParent());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "Could not create the recording folder.", e);
        }
        try (FileChannel ch = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            long onDisk = ch.size();
            if (onDisk < storedSize) {
                throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE,
                        "The recording file is shorter than what was stored (" + onDisk + " < " + storedSize + " bytes).");
            }
            if (onDisk > storedSize) {
                ch.truncate(storedSize);
            }
            long before = storedSize;
            ch.position(before);
            // A chunk is all or nothing: on any failure the file goes back to its size before the chunk, so the
            // client can send the same chunk again.
            try {
                byte[] buf = new byte[64 * 1024];
                long written = 0;
                int n;
                while ((n = chunk.read(buf)) > 0) {
                    written += n;
                    if (before + written > maxClipBytes) {
                        throw new ApiException(ErrorCode.FILE_TOO_LARGE, "This recording is too long.");
                    }
                    ByteBuffer b = ByteBuffer.wrap(buf, 0, n);
                    while (b.hasRemaining()) {
                        ch.write(b);
                    }
                }
                ch.force(false);
                return before + written;
            } catch (IOException | RuntimeException e) {
                ch.truncate(before);
                throw e;
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "Could not write the recording.", e);
        }
    }

    /**
     * Turns the partial file into the final one after checking it: it exists, is not empty and is a WebM file. The
     * final name is never overwritten. Returns its size. When the partial file is gone but the final one is there and
     * valid, an earlier attempt already renamed it (and its transaction was then rolled back): that file is the
     * result. Throws CONFLICT only when there is no valid media.
     */
    public long finish(String relativePath) {
        Path target = resolve(relativePath);
        Path part = part(target);
        if (!Files.exists(part)) {
            long done = verifiedSize(target);
            if (done > 0) {
                return done;
            }
        }
        long size = verifiedSize(part);
        if (size <= 0) {
            // Nothing usable: no partial file is left behind.
            try {
                Files.deleteIfExists(part);
            } catch (IOException e) {
                log.warn("Could not remove the invalid partial recording {}: {}", part, e.getMessage());
            }
            throw new ApiException(ErrorCode.CONFLICT, "The recording has no valid media data.");
        }
        moveNoReplace(part, target);
        return size;
    }

    /**
     * Keeps what arrived of a clip that was never finished: a valid partial file becomes the final file and its size
     * is returned; an empty or invalid one is removed and -1 is returned. An already final file is just measured.
     */
    public long salvage(String relativePath) {
        Path target = resolve(relativePath);
        Path part = part(target);
        try {
            if (!Files.exists(part)) {
                return Files.isRegularFile(target) ? Files.size(target) : -1;
            }
            long size = verifiedSize(part);
            if (size <= 0) {
                Files.deleteIfExists(part);
                return -1;
            }
            moveNoReplace(part, target);
            return size;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "Could not recover the recording.", e);
        }
    }

    /** The finished file, for reading. */
    public Path readable(String relativePath) {
        Path p = resolve(relativePath);
        if (!Files.isRegularFile(p)) {
            throw ApiException.notFound("Recording file");
        }
        return p;
    }

    Path root() {
        return root;
    }

    private static Path part(Path target) {
        return target.resolveSibling(target.getFileName() + PART_SUFFIX);
    }

    /** Size of a WebM file, or 0 when it is missing, empty or not WebM. */
    private static long verifiedSize(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return 0;
            }
            long size = Files.size(file);
            if (size < EBML_MAGIC.length) {
                return 0;
            }
            byte[] head = new byte[EBML_MAGIC.length];
            try (InputStream in = Files.newInputStream(file)) {
                if (in.readNBytes(head, 0, head.length) != head.length) {
                    return 0;
                }
            }
            return java.util.Arrays.equals(head, EBML_MAGIC) ? size : 0;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "Could not read the recording.", e);
        }
    }

    /**
     * A rename within the same folder that refuses an existing target (ATOMIC_MOVE is not used: on Linux it would
     * silently replace an existing file). An existing target is a storage problem, not bad media: the partial file is
     * kept and the clip stays open, so nothing is lost or marked FAILED.
     */
    private static void moveNoReplace(Path from, Path to) {
        try {
            Files.move(from, to);
        } catch (FileAlreadyExistsException e) {
            log.error("Not finalizing {}: a different file already has the name {}", from, to.getFileName());
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE,
                    "A different file already has this recording's name; the recording was kept.", e);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "Could not finalize the recording.", e);
        }
    }
}
