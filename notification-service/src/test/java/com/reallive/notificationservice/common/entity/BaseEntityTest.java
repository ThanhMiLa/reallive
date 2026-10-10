package com.reallive.notificationservice.common.entity;

import com.reallive.notificationservice.email.dto.message.EmailType;
import com.reallive.notificationservice.email.entity.EmailDeliveryEventEntity;
import com.reallive.notificationservice.email.entity.EmailDeliveryEventType;
import com.reallive.notificationservice.email.entity.EmailNotificationEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:common_base_entity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class BaseEntityTest {

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void generatesInheritedAuditTimestampsForBothEmailEntities() {
        var notification = notification();
        entityManager.persist(notification);
        var delivery = EmailDeliveryEventEntity.builder().notification(notification).eventType(EmailDeliveryEventType.DELIVERED)
                .providerTimestamp(Instant.parse("2026-10-10T01:00:00Z")).build();
        entityManager.persist(delivery);
        entityManager.flush();
        entityManager.clear();

        assertAuditTimestamps(entityManager.find(EmailNotificationEntity.class, notification.getId()));
        assertAuditTimestamps(entityManager.find(EmailDeliveryEventEntity.class, delivery.getId()));
    }

    @Test
    void updatingBusinessDataRefreshesUpdatedAtAndPreservesCreatedAt() {
        var notification = notification();
        entityManager.persist(notification);
        entityManager.flush();
        UUID id = notification.getId();
        entityManager.clear();
        jdbc.update("UPDATE email_notifications SET updated_at = issued_at WHERE id = ?", id);
        var stored = entityManager.find(EmailNotificationEntity.class, id);
        Instant createdAt = stored.getCreatedAt();
        Instant previousUpdatedAt = stored.getUpdatedAt();
        stored.setTemplateId("d-updated-template");
        entityManager.flush();
        entityManager.clear();

        var updated = entityManager.find(EmailNotificationEntity.class, id);
        assertEquals("d-updated-template", updated.getTemplateId());
        assertEquals(createdAt, updated.getCreatedAt());
        assertTrue(updated.getUpdatedAt().isAfter(previousUpdatedAt));
    }

    @Test
    void changingCreatedAtCannotOverwriteThePersistedCreationTime() {
        var notification = notification();
        entityManager.persist(notification);
        entityManager.flush();
        Instant createdAt = notification.getCreatedAt();
        notification.setCreatedAt(Instant.EPOCH);
        notification.setTemplateId("d-changed-template");
        entityManager.flush();
        entityManager.clear();

        assertEquals(createdAt, entityManager.find(EmailNotificationEntity.class, notification.getId()).getCreatedAt());
    }

    private EmailNotificationEntity notification() {
        return EmailNotificationEntity.builder().eventId(UUID.randomUUID()).userId(UUID.randomUUID())
                .emailType(EmailType.EMAIL_VERIFICATION).recipientEmail("recipient@example.com")
                .issuedAt(Instant.parse("2020-01-01T00:00:00Z")).build();
    }

    private void assertAuditTimestamps(BaseEntity entity) {
        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
        assertFalse(entity.getUpdatedAt().isBefore(entity.getCreatedAt()));
    }
}
