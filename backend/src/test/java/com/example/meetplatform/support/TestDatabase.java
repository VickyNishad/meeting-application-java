package com.example.meetplatform.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A real PostgreSQL for integration tests, started once per JVM. Uses Testcontainers when Docker is available
 * (CI) and falls back to embedded PostgreSQL binaries otherwise, so constraint behaviour (exclusion constraints,
 * partial unique indexes, SKIP LOCKED) is always tested against PostgreSQL itself - never an in-memory stand-in.
 */
public final class TestDatabase {

    private static volatile Connection instance;

    private TestDatabase() {
    }

    public static Connection get() {
        if (instance == null) {
            synchronized (TestDatabase.class) {
                if (instance == null) {
                    instance = start();
                }
            }
        }
        return instance;
    }

    private static Connection start() {
        if (dockerAvailable()) {
            PostgreSQLContainer container = new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("meet").withUsername("meet").withPassword("meet");
            container.start();
            return new Connection(container.getJdbcUrl(), container.getUsername(), container.getPassword(), "testcontainers");
        }
        try {
            EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (IOException ignored) {
                    // JVM is exiting
                }
            }));
            return new Connection(pg.getJdbcUrl("postgres", "postgres"), "postgres", "postgres", "embedded");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
        }
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    public record Connection(String url, String username, String password, String provider) {
    }
}
