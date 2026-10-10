package com.reallive.notificationservice.email.repository;

import com.reallive.notificationservice.email.dto.message.EmailType;
import com.reallive.notificationservice.email.entity.EmailDeliveryEventEntity;
import com.reallive.notificationservice.email.entity.EmailNotificationEntity;
import com.reallive.notificationservice.email.entity.EmailNotificationStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=validate")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EmailHistoryRepositoryTest {

    private static final String SCHEMA = "email_history_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant ISSUED_AT = Instant.parse("2026-10-10T01:02:03Z");

    @Autowired
    private EmailNotificationRepository notifications;
    @Autowired
    private EmailDeliveryEventRepository deliveryEvents;
    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("notification.test.database-url",
                "jdbc:h2:mem:email_history;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username", () -> System.getProperty("notification.test.database-username", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("notification.test.database-password", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "\"" + SCHEMA + "\"");
    }

    @AfterAll
    static void dropTestSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @ParameterizedTest
    @EnumSource(EmailNotificationStatus.class)
    void persistsNotificationFieldsAndEverySubmissionStatus(EmailNotificationStatus status) {
        EmailNotificationEntity notification = notification(UUID.randomUUID());
        notification.setStatus(status);
        notification.setTemplateId("d-test-template");
        notification.setExpiresAt(ISSUED_AT.plusSeconds(60));
        notification.setAttemptCount(2);
        notification.setLastHttpStatus((short) 202);
        notification.setLastError("Previous submission timed out");
        notification.setProviderMessageId("provider-submission-id");
        notifications.saveAndFlush(notification);

        EmailNotificationEntity reloaded = notifications.findByEventId(notification.getEventId()).orElseThrow();

        assertEquals(notification.getId(), reloaded.getId());
        assertEquals(notification.getUserId(), reloaded.getUserId());
        assertEquals(EmailType.EMAIL_VERIFICATION, reloaded.getEmailType());
        assertEquals("recipient@example.com", reloaded.getRecipientEmail());
        assertEquals("d-test-template", reloaded.getTemplateId());
        assertEquals(status, reloaded.getStatus());
        assertEquals(2, reloaded.getAttemptCount());
        assertEquals(Short.valueOf((short) 202), reloaded.getLastHttpStatus());
        assertEquals("Previous submission timed out", reloaded.getLastError());
        assertEquals("provider-submission-id", reloaded.getProviderMessageId());
        assertEquals(ISSUED_AT, reloaded.getIssuedAt());
        assertEquals(ISSUED_AT.plusSeconds(60), reloaded.getExpiresAt());
        assertNotNull(reloaded.getCreatedAt());
        assertNotNull(reloaded.getUpdatedAt());
        assertTrue(notifications.existsByEventId(notification.getEventId()));
    }

    @Test
    void allowsNotificationsWithoutExpiryOrProviderResponse() {
        EmailNotificationEntity saved = notifications.saveAndFlush(notification(UUID.randomUUID()));
        EmailNotificationEntity reloaded = notifications.findById(saved.getId()).orElseThrow();
        assertEquals(EmailNotificationStatus.PENDING, reloaded.getStatus());
        assertEquals(0, reloaded.getAttemptCount());
        assertNull(reloaded.getExpiresAt());
        assertNull(reloaded.getTemplateId());
        assertNull(reloaded.getProviderMessageId());
        assertNull(reloaded.getLastHttpStatus());
        assertNull(reloaded.getLastError());
    }

    @Test
    void duplicateKafkaEventCannotCreateAnotherNotification() {
        EmailNotificationEntity original = notifications.saveAndFlush(notification(UUID.randomUUID()));
        EmailNotificationEntity duplicate = notification(UUID.randomUUID());
        duplicate.setEventId(original.getEventId());
        assertThrows(DataIntegrityViolationException.class, () -> notifications.saveAndFlush(duplicate));
        assertEquals(original.getId(), notifications.findByEventId(original.getEventId()).orElseThrow().getId());
    }

    @Test
    void userHistoryIsPaginatedOrderedAndDoesNotIncludeOtherUsers() {
        UUID userId = UUID.randomUUID();
        EmailNotificationEntity earlier = notifications.saveAndFlush(notification(userId));
        EmailNotificationEntity later = notifications.saveAndFlush(notification(userId));
        notifications.saveAndFlush(notification(UUID.randomUUID()));
        jdbc.update("UPDATE " + table("email_notifications") + " SET created_at = issued_at WHERE id = ?", earlier.getId());
        jdbc.update("UPDATE " + table("email_notifications") + " SET created_at = ? WHERE id = ?",
                ISSUED_AT.plusSeconds(1).atOffset(ZoneOffset.UTC), later.getId());
        var firstPage = notifications.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1));
        var secondPage = notifications.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(1, 1));
        assertEquals(2, firstPage.getTotalElements());
        assertEquals(later.getId(), firstPage.getContent().getFirst().getId());
        assertEquals(earlier.getId(), secondPage.getContent().getFirst().getId());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "DISABLED")
    void databaseRejectsMissingOrUnknownSubmissionStatus(String status) {
        EmailNotificationEntity saved = notifications.saveAndFlush(notification(UUID.randomUUID()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE " + table("email_notifications") + " SET status = ? WHERE id = ?", status, saved.getId()));
        assertEquals(EmailNotificationStatus.PENDING, notifications.findById(saved.getId()).orElseThrow().getStatus());
    }

    @Test
    void databaseRejectsNegativeAttemptCount() {
        EmailNotificationEntity saved = notifications.saveAndFlush(notification(UUID.randomUUID()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE " + table("email_notifications") + " SET attempt_count = -1 WHERE id = ?", saved.getId()));
        assertEquals(0, notifications.findById(saved.getId()).orElseThrow().getAttemptCount());
    }

    @Test
    void deliveryEventPersistsProviderDetailsAndLinksBackToKafkaEvent() {
        EmailNotificationEntity notification = notification(UUID.randomUUID());
        notification.setStatus(EmailNotificationStatus.ACCEPTED);
        notifications.saveAndFlush(notification);
        EmailDeliveryEventEntity event = deliveryEvent(notification, UUID.randomUUID().toString(), "bounce", ISSUED_AT);
        event.setSgMessageId("provider-webhook-id");
        event.setResponse("550 unavailable");
        event.setReason("Mailbox unavailable");
        event.setSmtpStatus("5.1.1");
        deliveryEvents.saveAndFlush(event);

        var reloaded = deliveryEvents.findBySgEventId(event.getSgEventId()).orElseThrow();
        assertEquals(event.getId(), reloaded.getId());
        assertEquals(notification.getId(), reloaded.getNotification().getId());
        assertEquals("provider-webhook-id", reloaded.getSgMessageId());
        assertEquals("bounce", reloaded.getEventType());
        assertEquals(ISSUED_AT, reloaded.getProviderTimestamp());
        assertNotNull(reloaded.getReceivedAt());
        assertEquals("550 unavailable", reloaded.getResponse());
        assertEquals("Mailbox unavailable", reloaded.getReason());
        assertEquals("5.1.1", reloaded.getSmtpStatus());
        assertTrue(deliveryEvents.existsBySgEventId(event.getSgEventId()));
        assertEquals(EmailNotificationStatus.ACCEPTED,
                notifications.findByEventId(notification.getEventId()).orElseThrow().getStatus());
    }

    @Test
    void outOfOrderWebhookEventsAreKeptAndQueriedByProviderTime() {
        var notification = notifications.saveAndFlush(notification(UUID.randomUUID()));
        var delivered = deliveryEvents.saveAndFlush(deliveryEvent(notification, UUID.randomUUID().toString(),
                "delivered", ISSUED_AT.plusSeconds(2)));
        var processed = deliveryEvents.saveAndFlush(deliveryEvent(notification, UUID.randomUUID().toString(),
                "processed", ISSUED_AT));
        var other = notifications.saveAndFlush(notification(UUID.randomUUID()));
        deliveryEvents.saveAndFlush(deliveryEvent(other, UUID.randomUUID().toString(), "processed", ISSUED_AT));
        var history = deliveryEvents.findByNotificationIdOrderByProviderTimestampAscReceivedAtAsc(
                notification.getId(), PageRequest.of(0, 10));
        assertEquals(2, history.getTotalElements());
        assertEquals(List.of(processed.getId(), delivered.getId()),
                history.stream().map(EmailDeliveryEventEntity::getId).toList());
    }

    @Test
    void duplicateWebhookEventIdIsRejectedAcrossNotifications() {
        var first = notifications.saveAndFlush(notification(UUID.randomUUID()));
        var second = notifications.saveAndFlush(notification(UUID.randomUUID()));
        String sgEventId = UUID.randomUUID().toString();
        var original = deliveryEvents.saveAndFlush(deliveryEvent(first, sgEventId, "processed", ISSUED_AT));
        assertThrows(DataIntegrityViolationException.class, () -> deliveryEvents.saveAndFlush(
                deliveryEvent(second, sgEventId, "processed", ISSUED_AT)));
        assertEquals(original.getId(), deliveryEvents.findBySgEventId(sgEventId).orElseThrow().getId());
    }

    @Test
    void allowsMultipleWebhookEventsWithoutProviderEventIds() {
        var notification = notifications.saveAndFlush(notification(UUID.randomUUID()));
        deliveryEvents.saveAndFlush(deliveryEvent(notification, null, "processed", ISSUED_AT));
        deliveryEvents.saveAndFlush(deliveryEvent(notification, null, "delivered", ISSUED_AT.plusSeconds(1)));
        assertEquals(2, deliveryEvents.findByNotificationIdOrderByProviderTimestampAscReceivedAtAsc(
                notification.getId(), PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void deliveryEventMustReferenceAnExistingNotification() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO " + table("email_delivery_events")
                        + " (notification_id, event_type, provider_timestamp) VALUES (?, 'processed', CURRENT_TIMESTAMP)",
                UUID.randomUUID()));
    }

    @Test
    void returnsEmptyForUnknownEventIdentifiers() {
        UUID eventId = UUID.randomUUID();
        String sgEventId = UUID.randomUUID().toString();
        assertTrue(notifications.findByEventId(eventId).isEmpty());
        assertFalse(notifications.existsByEventId(eventId));
        assertTrue(deliveryEvents.findBySgEventId(sgEventId).isEmpty());
        assertFalse(deliveryEvents.existsBySgEventId(sgEventId));
    }

    private EmailNotificationEntity notification(UUID userId) {
        return EmailNotificationEntity.builder().eventId(UUID.randomUUID()).userId(userId)
                .emailType(EmailType.EMAIL_VERIFICATION).recipientEmail("recipient@example.com")
                .issuedAt(ISSUED_AT).build();
    }

    private EmailDeliveryEventEntity deliveryEvent(EmailNotificationEntity notification,
            String sgEventId, String eventType, Instant providerTimestamp) {
        return EmailDeliveryEventEntity.builder().notification(notification).sgEventId(sgEventId)
                .eventType(eventType).providerTimestamp(providerTimestamp).build();
    }

    private String table(String name) {
        return "\"" + SCHEMA + "\"." + name;
    }
}
