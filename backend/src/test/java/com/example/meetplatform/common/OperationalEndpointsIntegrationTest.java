package com.example.meetplatform.common;

import com.example.meetplatform.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Metrics and API documentation are not public by default. */
class OperationalEndpointsIntegrationTest extends AbstractIntegrationTest {

    @Test
    void prometheusEndpointRequiresTheScrapeCredential() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", basic("prometheus", "wrong")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", basic("admin", "test-scrape-password")))
                .andExpect(status().isUnauthorized());
        // Past security (the metrics exporter itself may be switched off in test contexts, hence not just 200).
        int status = mvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basic("prometheus", "test-scrape-password")))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    void openApiIsDisabledUnlessExplicitlyEnabled() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
