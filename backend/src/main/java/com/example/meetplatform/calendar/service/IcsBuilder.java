package com.example.meetplatform.calendar.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Minimal RFC 5545 calendar invitations (METHOD:REQUEST / CANCEL) that Google Calendar, Outlook and Apple
 * Calendar accept as e-mail attachments. Times are written in UTC; the UID is stable per meeting and the
 * SEQUENCE increases with each update so clients replace rather than duplicate the event.
 */
public final class IcsBuilder {

    public enum Method { REQUEST, CANCEL }

    /** Control characters other than HTAB, which RFC 5545 does not allow inside a content line. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t]]");
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private IcsBuilder() {
    }

    public record Event(UUID uid, long sequence, Instant start, Instant end, String summary, String description,
                        String url, String organizerName, String organizerEmail, List<String> attendeeEmails,
                        String recurrenceRule) {
    }

    public static String build(Method method, Event e, Instant now) {
        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "PRODID:-//Meet Platform//Meetings//EN");
        line(sb, "VERSION:2.0");
        line(sb, "CALSCALE:GREGORIAN");
        line(sb, "METHOD:" + method.name());
        line(sb, "BEGIN:VEVENT");
        line(sb, "UID:" + e.uid() + "@meet-platform");
        line(sb, "SEQUENCE:" + e.sequence());
        line(sb, "DTSTAMP:" + UTC.format(now));
        line(sb, "DTSTART:" + UTC.format(e.start()));
        line(sb, "DTEND:" + UTC.format(e.end()));
        if (e.recurrenceRule() != null) {
            line(sb, "RRULE:" + e.recurrenceRule());
        }
        line(sb, "SUMMARY:" + escape(e.summary()));
        if (e.description() != null) {
            line(sb, "DESCRIPTION:" + escape(e.description()));
        }
        if (e.url() != null) {
            line(sb, "URL:" + e.url());
            line(sb, "LOCATION:" + escape(e.url()));
        }
        if (e.organizerEmail() != null) {
            line(sb, "ORGANIZER;CN=" + quote(e.organizerName()) + ":mailto:" + e.organizerEmail());
        }
        for (String attendee : e.attendeeEmails()) {
            line(sb, "ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:" + attendee);
        }
        line(sb, "STATUS:" + (method == Method.CANCEL ? "CANCELLED" : "CONFIRMED"));
        line(sb, "END:VEVENT");
        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    /** TEXT value escaping (RFC 5545 3.3.11); every line break (CRLF, LF or a lone CR) becomes the {@code \n} escape. */
    static String escape(String text) {
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    /** Quoted parameter value (e.g. CN): may contain neither DQUOTE nor control characters (RFC 5545 3.1). */
    static String quote(String name) {
        return "\"" + (name == null ? "" : CONTROL.matcher(name.replace("\"", "'")).replaceAll(" ")) + "\"";
    }

    /**
     * Folds content lines at 75 octets as required by RFC 5545 section 3.1. Any control character left in the
     * content (e.g. CR/LF in an unescaped value) is replaced first, so no input can start a new content line.
     */
    static void line(StringBuilder sb, String content) {
        content = CONTROL.matcher(content).replaceAll(" ");
        int used = 0;
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + len > 75) {
                sb.append("\r\n ");
                used = 1; // the continuation line's leading space counts toward the limit
            }
            sb.appendCodePoint(cp);
            used += len;
            i += Character.charCount(cp);
        }
        sb.append("\r\n");
    }
}
