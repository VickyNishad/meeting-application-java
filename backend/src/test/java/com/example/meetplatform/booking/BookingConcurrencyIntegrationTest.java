package com.example.meetplatform.booking;

import com.example.meetplatform.booking.dto.BookingDtos.CreateBookingRequest;
import com.example.meetplatform.booking.entity.Booking;
import com.example.meetplatform.booking.repository.BookingRepository;
import com.example.meetplatform.booking.service.BookingService;
import com.example.meetplatform.common.exception.ApiException;
import com.example.meetplatform.common.exception.ErrorCode;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class BookingConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 12;

    @Autowired
    TestUsers users;

    @Autowired
    BookingService bookingService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BookingRepository bookingRepository;

    @Autowired
    TransactionTemplate tx;

    @Test
    void concurrentRequestsForTheSameSlotProduceExactlyOneBooking() throws Exception {
        SignedIn host = users.create(mvc, "Busy Host");
        String slug = setUpBookingPage(host);
        Instant slot = firstSlot(slug);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            int n = i;
            Callable<String> attempt = () -> {
                start.await();
                try {
                    bookingService.create(new CreateBookingRequest(slug, slot, "Invitee " + n,
                            "invitee" + n + "@example.test", "Asia/Kolkata", null), "10.0.0." + n);
                    return "OK";
                } catch (ApiException e) {
                    return e.code().name();
                } catch (DataIntegrityViolationException e) {
                    return "EXCLUSION_VIOLATION";
                }
            };
            results.add(pool.submit(attempt));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.equals("OK"))
                .allMatch(o -> o.equals(ErrorCode.SLOT_UNAVAILABLE.name()) || o.equals("EXCLUSION_VIOLATION"));
        assertThat(jdbc.queryForObject(
                "select count(*) from bookings where host_id = ? and status = 'CONFIRMED'", Long.class, host.id()))
                .isEqualTo(1L);
        // The slot is no longer offered.
        mvc.perform(get("/api/v1/booking-pages/public/" + slug + "/slots")
                        .param("from", LocalDate.ofInstant(slot, ZoneOffset.UTC).toString())
                        .param("to", LocalDate.ofInstant(slot, ZoneOffset.UTC).toString()).param("timeZone", "UTC"))
                .andExpect(jsonPath("$.slots[?(@.start == '" + slot + "')]").doesNotExist());
    }

    @Test
    void databaseExclusionConstraintRejectsOverlapsEvenWithoutTheApplication() throws Exception {
        SignedIn host = users.create(mvc, "Constraint Host");
        String slug = setUpBookingPage(host);
        UUID pageId = jdbc.queryForObject("select id from booking_pages where slug = ?", UUID.class, slug);
        Instant start = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        insertRaw(pageId, host.id(), start, start.plus(30, ChronoUnit.MINUTES), "CONFIRMED");
        assertThatThrownBy(() -> insertRaw(pageId, host.id(), start.plus(15, ChronoUnit.MINUTES),
                start.plus(45, ChronoUnit.MINUTES), "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Cancelled bookings do not block, and back-to-back bookings are allowed ([start, end) ranges).
        insertRaw(pageId, host.id(), start.plus(15, ChronoUnit.MINUTES), start.plus(45, ChronoUnit.MINUTES), "CANCELLED");
        insertRaw(pageId, host.id(), start.plus(30, ChronoUnit.MINUTES), start.plus(60, ChronoUnit.MINUTES), "CONFIRMED");
    }

    @Test
    void inviteeCanRescheduleAndCancelWithTheManageToken() throws Exception {
        SignedIn host = users.create(mvc, "Calendly Host");
        String slug = setUpBookingPage(host);
        Instant first = firstSlot(slug);

        JsonNode booking = json.readTree(mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "booking-key-" + UUID.randomUUID())
                        .content(toJson(Map.of("slug", slug, "startTime", first.toString(), "inviteeName", "Ivy",
                                "inviteeEmail", "ivy@example.test", "inviteeTimeZone", "America/New_York"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.joinUrl").exists())
                .andReturn().getResponse().getContentAsString());
        String token = booking.get("manageToken").asString();

        // The host was notified (in-app) and invitee/host e-mails with ICS were queued.
        assertThat(jdbc.queryForObject("select count(*) from notifications where reference_id = ? and channel = 'EMAIL'"
                + " and type = 'BOOKING_CONFIRMED' and ics_attachment like '%METHOD:REQUEST%'", Long.class,
                UUID.fromString(booking.get("id").asString()))).isEqualTo(2L);

        Instant later = first.plus(1, ChronoUnit.HOURS);
        JsonNode moved = json.readTree(mvc.perform(post("/api/v1/bookings/manage/" + token + "/reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("startTime", later.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startTime").value(later.toString()))
                .andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/v1/bookings/manage/" + token)).andExpect(jsonPath("$.status").value("RESCHEDULED"));

        String newToken = moved.get("manageToken").asString();
        mvc.perform(post("/api/v1/bookings/manage/" + newToken + "/cancel").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("reason", "conflict")))).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/bookings/manage/" + newToken + "/cancel"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_NOT_MODIFIABLE"));
        mvc.perform(get("/api/v1/bookings/manage/not-a-real-token")).andExpect(status().isNotFound());
    }

    /**
     * Regression: neighbouring 30-minute slots overlap through the 10-minute buffer, and concurrent inserts for them
     * could deadlock inside the exclusion-constraint check (SQLSTATE 40P01), which surfaced as 500.
     */
    @Test
    void concurrentBookingsOfNeighbouringSlotsNeverFailWithServerErrors() throws Exception {
        SignedIn host = users.create(mvc, "Adjacent Host");
        String slug = setUpBookingPage(host);
        List<String> outcomes = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            List<Instant> slots = slots(slug, LocalDate.now(ZoneOffset.UTC).plusDays(2 + round), 4);
            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                Instant slot = slots.get(i % slots.size());
                String email = "neighbour" + round + "-" + i + "@example.test";
                results.add(pool.submit(() -> {
                    start.await();
                    var response = mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("slug", slug, "startTime", slot.toString(), "inviteeName", "N",
                                    "inviteeEmail", email, "inviteeTimeZone", "UTC"))))
                            .andReturn().getResponse();
                    return response.getStatus() + (response.getStatus() == 201 ? ""
                            : " " + json.readTree(response.getContentAsString()).get("code").asString());
                }));
            }
            start.countDown();
            for (Future<String> f : results) {
                outcomes.add(f.get());
            }
            pool.shutdown();
        }

        assertThat(outcomes).allMatch(o -> o.equals("201") || o.equals("409 SLOT_UNAVAILABLE"));
        assertThat(outcomes).contains("201");
        // Whatever won, the confirmed bookings (buffers included) never overlap.
        List<Map<String, Object>> confirmed = jdbc.queryForList("select blocked_start, blocked_end from bookings"
                + " where host_id = ? and status = 'CONFIRMED' order by blocked_start", host.id());
        for (int i = 1; i < confirmed.size(); i++) {
            assertThat(((Timestamp) confirmed.get(i).get("blocked_start")).toInstant())
                    .isAfterOrEqualTo(((Timestamp) confirmed.get(i - 1).get("blocked_end")).toInstant());
        }
    }

    @Test
    void constraintViolationsDoNotLogInviteePersonalData(CapturedOutput output) throws Exception {
        SignedIn host = users.create(mvc, "Private Host");
        String slug = setUpBookingPage(host);
        UUID pageId = jdbc.queryForObject("select id from booking_pages where slug = ?", UUID.class, slug);
        Instant start = Instant.now().plus(12, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        insertRaw(pageId, host.id(), start, start.plus(30, ChronoUnit.MINUTES), "CONFIRMED");
        String canary = "pii-canary-" + UUID.randomUUID().toString().substring(0, 8);

        Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(s -> bookingRepository.saveAndFlush(
                new Booking(pageId, host.id(), start.plus(15, ChronoUnit.MINUTES), start.plus(45, ChronoUnit.MINUTES),
                        start.plus(15, ChronoUnit.MINUTES), start.plus(45, ChronoUnit.MINUTES), canary + " Name",
                        canary + "@example.test", "UTC", null, UUID.randomUUID().toString().replace("-", "")))));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(canary);
            if (t instanceof SQLException sql) {
                for (SQLException next = sql.getNextException(); next != null; next = next.getNextException()) {
                    assertThat(String.valueOf(next.getMessage())).doesNotContain(canary);
                }
            }
        }
        // The failure is still reported (SQLSTATE and constraint), just without the bound values.
        assertThat(output.getAll()).contains("ex_bookings_no_overlap").doesNotContain(canary);
    }

    @Test
    void bookingPageWithUpcomingBookingsCannotBeDeleted() throws Exception {
        SignedIn host = users.create(mvc, "Tidy Host");
        String slug = setUpBookingPage(host);
        UUID pageId = jdbc.queryForObject("select id from booking_pages where slug = ?", UUID.class, slug);
        String token = json.readTree(mvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("slug", slug, "startTime", firstSlot(slug).toString(),
                                "inviteeName", "Una", "inviteeEmail", "una@example.test", "inviteeTimeZone", "UTC"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("manageToken").asString();

        mvc.perform(delete("/api/v1/booking-pages/" + pageId).header("Authorization", host.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_PAGE_HAS_UPCOMING_BOOKINGS"));
        mvc.perform(get("/api/v1/bookings/manage/" + token)).andExpect(jsonPath("$.status").value("CONFIRMED"));

        mvc.perform(post("/api/v1/bookings/manage/" + token + "/cancel")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/booking-pages/" + pageId).header("Authorization", host.bearer()))
                .andExpect(status().isNoContent());
    }

    private List<Instant> slots(String slug, LocalDate day, int count) throws Exception {
        JsonNode slots = json.readTree(mvc.perform(get("/api/v1/booking-pages/public/" + slug + "/slots")
                        .param("from", day.toString()).param("to", day.toString()).param("timeZone", "UTC"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("slots");
        assertThat(slots.size()).isGreaterThanOrEqualTo(count);
        List<Instant> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(Instant.parse(slots.get(i).get("start").asString()));
        }
        return result;
    }

    private String setUpBookingPage(SignedIn host) throws Exception {
        List<Map<String, String>> rules = new ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")) {
            rules.add(Map.of("dayOfWeek", day, "startTime", "08:00", "endTime", "20:00"));
        }
        mvc.perform(put("/api/v1/availability").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("timeZone", "UTC", "weeklyRules", rules))))
                .andExpect(status().isOk());
        String slug = "talk-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/booking-pages").header("Authorization", host.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of(
                                "slug", slug, "title", "Intro call", "durationMinutes", 30,
                                "minNoticeMinutes", 60, "bufferAfterMinutes", 10))))
                .andExpect(status().isCreated());
        return slug;
    }

    private Instant firstSlot(String slug) throws Exception {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).plusDays(2);
        JsonNode slots = json.readTree(mvc.perform(get("/api/v1/booking-pages/public/" + slug + "/slots")
                        .param("from", day.toString()).param("to", day.toString()).param("timeZone", "UTC"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(slots.get("slots").size()).isGreaterThan(2);
        return Instant.parse(slots.get("slots").get(0).get("start").asString());
    }

    private void insertRaw(UUID pageId, UUID hostId, Instant start, Instant end, String status) {
        jdbc.update("""
                insert into bookings (id, booking_page_id, host_id, status, start_time, end_time, blocked_start, blocked_end,
                    invitee_name, invitee_email, invitee_time_zone, manage_token_hash, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'X', 'x@example.test', 'UTC', ?, now(), now())
                """, UUID.randomUUID(), pageId, hostId, status, Timestamp.from(start), Timestamp.from(end),
                Timestamp.from(start), Timestamp.from(end), UUID.randomUUID().toString().replace("-", ""));
    }
}
