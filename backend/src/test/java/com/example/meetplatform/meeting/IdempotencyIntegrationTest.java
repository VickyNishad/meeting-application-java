package com.example.meetplatform.meeting;

import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import com.example.meetplatform.support.TestUsers.SignedIn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Regression: concurrent requests with the same Idempotency-Key used to all run (duplicate meetings). */
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 10;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void concurrentRequestsWithTheSameKeyCreateOneMeeting() throws Exception {
        SignedIn host = users.create(mvc, "Double Clicker");
        String key = "create-" + UUID.randomUUID();
        String title = "Exactly once " + UUID.randomUUID().toString().substring(0, 8);
        String body = toJson(Map.of("title", title, "type", "INSTANT", "timeZone", "UTC"));

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> results = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse();
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<MockHttpServletResponse> f : results) {
            MockHttpServletResponse r = f.get();
            outcomes.add(r.getStatus() == 201 ? "201"
                    : r.getStatus() + " " + json.readTree(r.getContentAsString()).get("code").asString());
        }
        pool.shutdown();

        assertThat(outcomes).allMatch(o -> o.equals("201") || o.equals("409 IDEMPOTENCY_IN_PROGRESS"));
        assertThat(jdbc.queryForObject("select count(*) from meetings where title = ?", Long.class, title))
                .isEqualTo(1L);

        // Once finished, a retry replays the stored response; the same key with another body is still a conflict.
        String createdId = jdbc.queryForObject("select id from meetings where title = ?", UUID.class, title).toString();
        mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(createdId));
        mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Something else", "type", "INSTANT", "timeZone", "UTC"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void failedRequestReleasesTheKey() throws Exception {
        SignedIn host = users.create(mvc, "Retrier");
        String key = "retry-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Wrong type", "type", "PERSONAL_ROOM", "timeZone", "UTC"))))
                .andExpect(status().isBadRequest());
        // The failed attempt did not keep the key reserved (otherwise: 409 for the different body).
        mvc.perform(post("/api/v1/meetings").header("Authorization", host.bearer()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("title", "Right type", "type", "INSTANT", "timeZone", "UTC"))))
                .andExpect(status().isCreated());
    }
}
