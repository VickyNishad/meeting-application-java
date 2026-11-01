package com.example.meetplatform.file.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FileSignatureTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Test
    void typesWithAWellKnownSignatureMustStartWithIt() {
        assertThat(FileService.contentMatches("application/pdf", ascii("%PDF-1.7\n"))).isTrue();
        assertThat(FileService.contentMatches("image/png", bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0))).isTrue();
        assertThat(FileService.contentMatches("image/jpeg", bytes(0xFF, 0xD8, 0xFF, 0xE0))).isTrue();
        assertThat(FileService.contentMatches("image/gif", ascii("GIF89a...."))).isTrue();
        assertThat(FileService.contentMatches("image/webp", ascii("RIFF\0\0\0\0WEBPVP8 "))).isTrue();
        assertThat(FileService.contentMatches(DOCX, bytes('P', 'K', 3, 4, 20, 0))).isTrue();
        assertThat(FileService.contentMatches("application/zip", bytes('P', 'K', 5, 6, 0, 0))).isTrue();

        assertThat(FileService.contentMatches("image/png", ascii("<html><script>alert(1)</script>"))).isFalse();
        assertThat(FileService.contentMatches("application/pdf", ascii("MZ\u0090\0"))).isFalse();
        assertThat(FileService.contentMatches("image/webp", ascii("RIFF\0\0\0\0WAVEfmt "))).isFalse();
        assertThat(FileService.contentMatches(DOCX, ascii("%PDF-1.7"))).isFalse();
        assertThat(FileService.contentMatches("image/jpeg", bytes(0xFF))).isFalse();
        assertThat(FileService.contentMatches("image/gif", new byte[0])).isFalse();
    }

    @Test
    void typesWithoutASignatureAreNotContentChecked() {
        assertThat(FileService.contentMatches("text/plain", ascii("hello"))).isTrue();
        assertThat(FileService.contentMatches("text/csv", ascii("a,b\n1,2"))).isTrue();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }
}
