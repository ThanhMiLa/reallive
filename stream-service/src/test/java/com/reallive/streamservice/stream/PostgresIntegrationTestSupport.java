package com.reallive.streamservice.stream;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public abstract class PostgresIntegrationTestSupport {

    private static final String SYSTEM_PROPERTY_URL = System.getProperty("stream.test.database-url");
    private static final PostgreSQLContainer<?> POSTGRES;

    static {
        if (SYSTEM_PROPERTY_URL == null || SYSTEM_PROPERTY_URL.isBlank()) {
            POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("stream_test")
                    .withUsername("stream_test")
                    .withPassword("stream_test");
            POSTGRES.start();
        } else {
            POSTGRES = null;
        }
    }

    protected static String newSchema() {
        return "stream_it_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static HikariDataSource createTestDataSource(int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES == null ? SYSTEM_PROPERTY_URL : POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES == null
                ? System.getProperty("stream.test.database-username", "stream_service") : POSTGRES.getUsername());
        config.setPassword(POSTGRES == null
                ? System.getProperty("stream.test.database-password", "stream_password") : POSTGRES.getPassword());
        config.setMaximumPoolSize(poolSize);
        return new HikariDataSource(config);
    }

    protected static void configureDatabase(DynamicPropertyRegistry registry, String schema) {
        MediaTestSupport.register(registry);
        registry.add("spring.datasource.url", () -> POSTGRES == null ? SYSTEM_PROPERTY_URL : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> POSTGRES == null
                ? System.getProperty("stream.test.database-username", "stream_service") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> POSTGRES == null
                ? System.getProperty("stream.test.database-password", "stream_password") : POSTGRES.getPassword());
        registry.add("spring.datasource.hikari.schema", () -> schema);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> schema);
        registry.add("spring.flyway.default-schema", () -> schema);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
        registry.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
    }

    protected static void dropSchema(String schema) throws SQLException {
        if (!schema.matches("stream_it_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("Only an isolated streaming test schema may be dropped");
        }
        try (HikariDataSource ds = createTestDataSource(1);
             Connection conn = ds.getConnection();
             Statement statement = conn.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }
}
