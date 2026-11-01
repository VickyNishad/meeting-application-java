package com.example.meetplatform.recording;

import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.recording.service.SpeechClipStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Folder layout, names, sanitizing, path containment and file finalization of speaking clips. */
class SpeechClipStorageTest {

    private static final byte[] WEBM = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2, 3, 4};
    private static final Instant START = Instant.parse("2026-10-31T09:15:30Z");
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    @TempDir
    Path root;

    private SpeechClipStorage storage() {
        return new SpeechClipStorage(root.toString(), 0);
    }

    @Test
    void layoutIsDateMeetingParticipantAndTheNameIsTheStartTimeInTheMeetingZone() {
        String path = storage().relativePath(START, KOLKATA, "Day 1: Kick-off", "Vicky", "8f72a1");
        // 09:15:30 UTC = 14:45:30 in Kolkata.
        assertThat(path).isEqualTo("31-10-2026/Day_1_Kick-off/Vicky/311026144530_8f72a1.webm");
        // Near midnight the meeting's zone decides the day: 20:00 UTC on the 31st is 01:30 on 1 November in Kolkata.
        assertThat(storage().relativePath(Instant.parse("2026-10-31T20:00:00Z"), KOLKATA, "M", "P", "abc123"))
                .startsWith("01-11-2026/M/P/011126013000_");
    }

    @Test
    void clipsInTheSameSecondGetDifferentNames() {
        Set<String> names = new HashSet<>();
        for (String id : new String[] {"000001", "000002", "abcdef"}) {
            names.add(SpeechClipStorage.fileName(START, ZoneId.of("UTC"), id));
        }
        assertThat(names).hasSize(3).allMatch(n -> n.startsWith("311026091530_"));
    }

    @Test
    void namesAreSanitizedIntoSingleSafeFolderNames() {
        assertThat(SpeechClipStorage.sanitize("../../etc/passwd", "X")).isEqualTo("etcpasswd");
        assertThat(SpeechClipStorage.sanitize("..\\..\\Windows\\System32", "X")).isEqualTo("WindowsSystem32");
        assertThat(SpeechClipStorage.sanitize("Q3 review: <draft> & \"final\"?*|", "X")).isEqualTo("Q3_review_draft_final");
        assertThat(SpeechClipStorage.sanitize("Rahul Sharma", "X")).isEqualTo("Rahul_Sharma");
        assertThat(SpeechClipStorage.sanitize("राहुल शर्मा", "X")).isEqualTo("राहुल_शर्मा");
        assertThat(SpeechClipStorage.sanitize("José Ñúñez", "X")).isEqualTo("José_Ñúñez");
        assertThat(SpeechClipStorage.sanitize("...", "Participant")).isEqualTo("Participant");
        assertThat(SpeechClipStorage.sanitize("", "Meeting")).isEqualTo("Meeting");
        assertThat(SpeechClipStorage.sanitize(null, "Meeting")).isEqualTo("Meeting");
        assertThat(SpeechClipStorage.sanitize("CON", "X")).isEqualTo("_CON");
        assertThat(SpeechClipStorage.sanitize("nul", "X")).isEqualTo("_nul");
        assertThat(SpeechClipStorage.sanitize("a".repeat(100), "X")).hasSize(60);
        assertThat(SpeechClipStorage.sanitize("tab\tand\nnewline", "X")).isEqualTo("tab_and_newline");
    }

    @Test
    void aPathOutsideTheRootIsRefused() {
        SpeechClipStorage s = storage();
        for (String evil : new String[] {"../outside.webm", "a/../../outside.webm", "..", "/etc/passwd", ""}) {
            assertThatThrownBy(() -> s.readable(evil)).isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
        assertThat(Files.exists(root.getParent().resolve("outside.webm"))).isFalse();
    }

    @Test
    void chunksBuildAPartialFileThatBecomesTheClipOnlyWhenVerified() throws Exception {
        SpeechClipStorage s = storage();
        String path = s.relativePath(START, KOLKATA, "Weekly", "Vicky", "aaaaaa");
        assertThat(s.append(path, new ByteArrayInputStream(WEBM), 0, 1_000)).isEqualTo(WEBM.length);
        assertThat(s.append(path, new ByteArrayInputStream(new byte[] {9, 9}), WEBM.length, 1_000)).isEqualTo(WEBM.length + 2);
        Path finalFile = root.resolve(path);
        assertThat(finalFile).doesNotExist();
        assertThat(finalFile.resolveSibling(finalFile.getFileName() + ".part")).exists();

        assertThat(s.finish(path)).isEqualTo(WEBM.length + 2);
        assertThat(finalFile).exists();
        assertThat(Files.size(finalFile)).isEqualTo(WEBM.length + 2);
        assertThat(s.exists(path)).isTrue();
    }

    @Test
    void anExistingFileIsNeverOverwritten() throws Exception {
        SpeechClipStorage s = storage();
        String path = s.relativePath(START, KOLKATA, "Weekly", "Rahul", "bbbbbb");
        Path finalFile = root.resolve(path);
        Files.createDirectories(finalFile.getParent());
        Files.write(finalFile, new byte[] {42});
        s.append(path, new ByteArrayInputStream(WEBM), 0, 1_000);
        // A storage problem, not bad media (CONFLICT would mark the clip FAILED): the recording is kept as it is.
        assertThatThrownBy(() -> s.finish(path)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.STORAGE_UNAVAILABLE));
        assertThat(Files.readAllBytes(finalFile)).containsExactly(42);
        assertThat(Files.readAllBytes(root.resolve(path + ".part"))).containsExactly(WEBM);
    }

    @Test
    void aNameIsNeverCutInsideACharacter() {
        // QA RC-1: "𠀀" is two UTF-16 units; cutting at 60 units left a lone surrogate, an invalid path on Windows.
        String cut = SpeechClipStorage.sanitize("a".repeat(59) + "𠀀" + "tail", "X");
        assertThat(cut).isEqualTo("a".repeat(59) + "𠀀");
        assertThat(SpeechClipStorage.sanitize("a".repeat(60) + "𠀀", "X")).isEqualTo("a".repeat(60));
        String wide = SpeechClipStorage.sanitize("𠀀".repeat(70), "X");
        assertThat(wide.codePointCount(0, wide.length())).isEqualTo(60);
        assertThat(wide.chars().filter(c -> Character.isSurrogate((char) c)).count()).isEqualTo(120);
        Path folder = root.resolve(cut);
        assertThat(folder.getFileName().toString()).isEqualTo(cut);
    }

    @Test
    void aStoredPathThatIsNotValidHereIsNotFoundInsteadOfAnUnexpectedError() {
        assertThatThrownBy(() -> storage().readable("31-10-2026/bad\u0000name/x.webm")).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> storage().salvage("31-10-2026/bad\u0000name/x.webm")).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void aRetriedChunkIsWrittenAtTheStoredSizeNeverTwice() throws Exception {
        // QA RC-4: chunk 1 reached the disk but its transaction rolled back (the database still says 8 bytes).
        SpeechClipStorage s = storage();
        String path = s.relativePath(START, KOLKATA, "Weekly", "Vicky", "jjjjjj");
        s.append(path, new ByteArrayInputStream(WEBM), 0, 1_000);
        s.append(path, new ByteArrayInputStream(new byte[] {9, 9}), WEBM.length, 1_000);
        assertThat(s.append(path, new ByteArrayInputStream(new byte[] {9, 9}), WEBM.length, 1_000)).isEqualTo(WEBM.length + 2);
        byte[] expected = java.util.Arrays.copyOf(WEBM, WEBM.length + 2);
        expected[WEBM.length] = 9;
        expected[WEBM.length + 1] = 9;
        assertThat(Files.readAllBytes(root.resolve(path + ".part"))).containsExactly(expected);
        // A file shorter than what the database recorded has lost data: refused, nothing is written.
        assertThatThrownBy(() -> s.append(path, new ByteArrayInputStream(WEBM), 100, 1_000)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.STORAGE_UNAVAILABLE));
        assertThat(Files.size(root.resolve(path + ".part"))).isEqualTo(WEBM.length + 2);
    }

    @Test
    void finishingAgainAfterARolledBackFinishKeepsTheFinalFile() throws Exception {
        // QA RC-5: the rename succeeded but the commit failed; the retry must not report "no valid media".
        SpeechClipStorage s = storage();
        String path = s.relativePath(START, KOLKATA, "Weekly", "Vicky", "kkkkkk");
        s.append(path, new ByteArrayInputStream(WEBM), 0, 1_000);
        assertThat(s.finish(path)).isEqualTo(WEBM.length);
        assertThat(s.finish(path)).isEqualTo(WEBM.length);
        assertThat(Files.readAllBytes(root.resolve(path))).containsExactly(WEBM);
    }

    @Test
    void somethingThatIsNotWebmIsNotFinalized() throws Exception {
        SpeechClipStorage s = storage();
        String path = s.relativePath(START, KOLKATA, "Weekly", "Mallory", "cccccc");
        s.append(path, new ByteArrayInputStream("<html>not media</html>".getBytes()), 0, 1_000);
        assertThatThrownBy(() -> s.finish(path)).isInstanceOf(ApiException.class);
        assertThat(root.resolve(path)).doesNotExist();
        assertThat(root.resolve(path + ".part")).doesNotExist();
    }

    @Test
    void anUnfinishedClipIsSalvagedWhenValidAndRemovedWhenNot() throws Exception {
        SpeechClipStorage s = storage();
        String good = s.relativePath(START, KOLKATA, "Weekly", "Vicky", "dddddd");
        s.append(good, new ByteArrayInputStream(WEBM), 0, 1_000);
        assertThat(s.salvage(good)).isEqualTo(WEBM.length);
        assertThat(root.resolve(good)).exists();

        String bad = s.relativePath(START, KOLKATA, "Weekly", "Vicky", "eeeeee");
        s.append(bad, new ByteArrayInputStream(new byte[] {1, 2, 3}), 0, 1_000);
        assertThat(s.salvage(bad)).isEqualTo(-1);
        assertThat(root.resolve(bad + ".part")).doesNotExist();
        assertThat(s.salvage(s.relativePath(START, KOLKATA, "Weekly", "Nobody", "ffffff"))).isEqualTo(-1);
    }

    @Test
    void aChunkOverTheClipLimitIsRejectedAndLeavesTheFileAsItWas() throws Exception {
        SpeechClipStorage s = storage();
        String path = s.relativePath(START, KOLKATA, "Weekly", "Vicky", "gggggg");
        s.append(path, new ByteArrayInputStream(WEBM), 0, 10);
        assertThatThrownBy(() -> s.append(path, new ByteArrayInputStream(new byte[5]), WEBM.length, 10))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.FILE_TOO_LARGE));
        assertThat(Files.size(root.resolve(path + ".part"))).isEqualTo(WEBM.length);
    }

    @Test
    void aFullDiskRefusesNewData() {
        SpeechClipStorage s = new SpeechClipStorage(root.toString(), Long.MAX_VALUE);
        String path = s.relativePath(START, KOLKATA, "Weekly", "Vicky", "hhhhhh");
        assertThatThrownBy(() -> s.append(path, new ByteArrayInputStream(WEBM), 0, 1_000))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INSUFFICIENT_STORAGE));
        assertThat(root.resolve(path + ".part")).doesNotExist();
    }

    @Test
    void aRootThatCannotHoldFoldersIsReportedAsUnavailable() throws Exception {
        Path notAFolder = Files.write(root.resolve("i-am-a-file"), new byte[] {1});
        SpeechClipStorage s = new SpeechClipStorage(notAFolder.toString(), 0);
        String path = s.relativePath(START, KOLKATA, "Weekly", "Vicky", "iiiiii");
        assertThatThrownBy(() -> s.append(path, new ByteArrayInputStream(WEBM), 0, 1_000))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.STORAGE_UNAVAILABLE));
    }
}
