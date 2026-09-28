package com.smartapi.gateway.integration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need a real PostgreSQL 17. One database per test JVM:
 * <ul>
 *   <li>Docker available (CI): a {@code postgres:17-alpine} container via <b>Testcontainers</b>;</li>
 *   <li>otherwise: embedded-postgres, the same PostgreSQL 17 server binaries started as a local
 *       process (used on the development machine, which has no Docker).</li>
 * </ul>
 * Either way Flyway applies the real migrations, so tests exercise the real schema, constraints and SQL.
 */
public abstract class PostgresIntegrationTest {

    private static final String JDBC_URL;
    private static final String USERNAME;
    private static final String PASSWORD;

    static {
        if (dockerAvailable()) {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:17-alpine");
            container.start(); // removed by Testcontainers' Ryuk sidecar when the JVM exits
            JDBC_URL = container.getJdbcUrl();
            USERNAME = container.getUsername();
            PASSWORD = container.getPassword();
            LoggerFactory.getLogger(PostgresIntegrationTest.class).info("PostgreSQL via Testcontainers: {}", JDBC_URL);
        } else {
            try {
                EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
                JDBC_URL = postgres.getJdbcUrl("postgres", "postgres");
                USERNAME = "postgres";
                PASSWORD = "postgres";
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
            }
        }
    }

    private static boolean dockerAvailable() {
        if ("false".equalsIgnoreCase(System.getenv("USE_TESTCONTAINERS"))) {
            return false;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }
}
