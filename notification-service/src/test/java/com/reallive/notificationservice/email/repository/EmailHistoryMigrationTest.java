package com.reallive.notificationservice.email.repository;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailHistoryMigrationTest {

    @Test
    void upgradesV1AndBackfillsAuditTimestampsWithoutChangingDeliveryHistory() {
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(System.getProperty("notification.test.database-url",
                "jdbc:h2:mem:email_audit_migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        dataSource.setUsername(System.getProperty("notification.test.database-username", "sa"));
        dataSource.setPassword(System.getProperty("notification.test.database-password", ""));
        dataSource.setMaximumPoolSize(2);
        String schema = "email_audit_" + UUID.randomUUID().toString().replace("-", "");
        var jdbc = new JdbcTemplate(dataSource);
        String notificationTable = "\"" + schema + "\".email_notifications";
        String deliveryTable = "\"" + schema + "\".email_delivery_events";
        UUID notificationId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        OffsetDateTime receivedAt = Instant.parse("2026-10-09T01:02:03Z").atOffset(ZoneOffset.UTC);
        try {
            assertEquals(1, migrations(dataSource, schema).target("1").load().migrate().migrationsExecuted);
            jdbc.update("INSERT INTO " + notificationTable
                            + " (id, event_id, user_id, email_type, recipient_email, issued_at) VALUES (?, ?, ?, 'EMAIL_VERIFICATION', ?, ?)",
                    notificationId, UUID.randomUUID(), UUID.randomUUID(), "test@example.com", receivedAt);
            jdbc.update("INSERT INTO " + deliveryTable
                            + " (id, notification_id, sg_event_id, event_type, provider_timestamp, received_at) VALUES (?, ?, ?, 'delivered', ?, ?)",
                    deliveryId, notificationId, "old-event", receivedAt.minusSeconds(1), receivedAt);
            Flyway flyway = migrations(dataSource, schema).load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            flyway.validate();
            assertEquals(0, flyway.migrate().migrationsExecuted);
            assertEquals(notificationId, jdbc.queryForObject("SELECT notification_id FROM " + deliveryTable + " WHERE id = ?", UUID.class, deliveryId));
            assertEquals("delivered", jdbc.queryForObject("SELECT event_type FROM " + deliveryTable + " WHERE id = ?", String.class, deliveryId));
            for (String column : new String[]{"received_at", "created_at", "updated_at"}) {
                assertEquals(receivedAt.toInstant(), jdbc.queryForObject(
                        "SELECT " + column + " FROM " + deliveryTable + " WHERE id = ?", OffsetDateTime.class, deliveryId).toInstant());
            }
        } finally {
            try {
                jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
            } finally {
                dataSource.close();
            }
        }
    }

    private FluentConfiguration migrations(HikariDataSource dataSource, String schema) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration");
    }
}
