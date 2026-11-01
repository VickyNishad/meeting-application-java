package com.example.meetplatform.calendar;

import com.example.meetplatform.calendar.service.IcsBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** No user-controlled text may start a new ICS content line (property injection). */
class IcsBuilderTest {

    private static final Instant START = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void lineBreaksInTextAndParametersCannotInjectProperties() {
        String ics = IcsBuilder.build(IcsBuilder.Method.REQUEST, new IcsBuilder.Event(UUID.randomUUID(), 1, START,
                START.plusSeconds(1800),
                "Sync\rATTENDEE:mailto:cr@evil.test",
                "Agenda\nATTACH:http://evil.test/a\r\nX-EVIL:1",
                "https://meet.example/j/abc",
                "Mallory\"\r\nATTENDEE;RSVP=TRUE:mailto:organizer@evil.test",
                "host@example.test", List.of("guest@example.test"), null), START);

        // Content lines only ever end with CRLF; there is no bare CR or LF anywhere.
        assertThat(ics.replace("\r\n", "")).doesNotContain("\r").doesNotContain("\n");
        // After unfolding: exactly one ATTENDEE (the real guest) and no injected properties.
        String unfolded = ics.replace("\r\n ", "");
        List<String> lines = Arrays.asList(unfolded.split("\r\n"));
        assertThat(lines).filteredOn(l -> l.startsWith("ATTENDEE")).hasSize(1)
                .allMatch(l -> l.endsWith("mailto:guest@example.test"));
        assertThat(lines).noneMatch(l -> l.startsWith("ATTACH") || l.startsWith("X-EVIL"));
        assertThat(unfolded).contains("SUMMARY:Sync\\nATTENDEE:mailto:cr@evil.test");
        assertThat(unfolded).contains("DESCRIPTION:Agenda\\nATTACH:http://evil.test/a\\nX-EVIL:1");
        assertThat(unfolded).contains("ORGANIZER;CN=\"Mallory'  ATTENDEE;RSVP=TRUE:mailto:organizer@evil.test\":mailto:host@example.test");
    }

    @Test
    void textEscapingCoversEveryLineBreakForm() {
        assertThat(escape("a\r\nb\nc\rd")).isEqualTo("a\\nb\\nc\\nd");
        assertThat(escape("x;y,z\\")).isEqualTo("x\\;y\\,z\\\\");
    }

    private static String escape(String s) {
        String ics = IcsBuilder.build(IcsBuilder.Method.CANCEL, new IcsBuilder.Event(UUID.randomUUID(), 0, START,
                START.plusSeconds(60), s, null, null, null, null, List.of(), null), START);
        String summary = Arrays.stream(ics.replace("\r\n ", "").split("\r\n"))
                .filter(l -> l.startsWith("SUMMARY:")).findFirst().orElseThrow();
        return summary.substring("SUMMARY:".length());
    }
}
