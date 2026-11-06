package com.example.meetplatform.support;

import com.example.meetplatform.common.storage.ObjectStorage;
import com.example.meetplatform.media.service.MediaService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Full application context against a real PostgreSQL schema built by Flyway.
 *
 * <p>The media server and object storage are external infrastructure that is not available in unit-test runs,
 * so their ports are replaced with Mockito mocks here. Their adapters are covered separately: token signing
 * and webhook signature verification run against the real LiveKit SDK in {@code LiveKitMediaAdapterTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @MockitoBean
    protected MediaService media;

    @MockitoBean
    protected ObjectStorage storage;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    /** Objects exist unless a test says otherwise (ObjectStorage is a mock here). */
    @BeforeEach
    void objectsExist() {
        when(storage.exists(anyString(), anyString())).thenReturn(true);
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.Connection db = TestDatabase.get();
        registry.add("spring.datasource.url", db::url);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    protected String toJson(Object value) {
        return json.writeValueAsString(value);
    }
}
