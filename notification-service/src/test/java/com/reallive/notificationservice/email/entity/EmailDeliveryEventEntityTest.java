package com.reallive.notificationservice.email.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailDeliveryEventEntityTest {

    @Test
    void sameIdDefinesEqualityRegardlessOfRelationshipAndAuditFields() {
        UUID id = UUID.randomUUID();
        var first = EmailDeliveryEventEntity.builder().id(id).eventType(EmailDeliveryEventType.PROCESSED)
                .notification(new EmailNotificationEntity()).build();
        var second = EmailDeliveryEventEntity.builder().id(id).eventType(EmailDeliveryEventType.DELIVERED).build();
        first.setCreatedAt(Instant.parse("2026-10-10T00:00:00Z"));
        second.setUpdatedAt(Instant.parse("2026-10-10T01:00:00Z"));
        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void distinctAndTransientEntitiesAreNotEqual() {
        var first = new EmailDeliveryEventEntity();
        var second = new EmailDeliveryEventEntity();
        assertEquals(first, first);
        assertNotEquals(first, second);
        first.setId(UUID.randomUUID());
        second.setId(UUID.randomUUID());
        assertNotEquals(first, second);
        assertNotEquals(first, null);
        assertNotEquals(first, new EmailNotificationEntity());
    }

    @Test
    void hashSetMembershipSurvivesIdentifierAndAuditUpdates() {
        var entity = new EmailDeliveryEventEntity();
        Set<EmailDeliveryEventEntity> entities = new HashSet<>();
        entities.add(entity);
        entity.setId(UUID.randomUUID());
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setNotification(new EmailNotificationEntity());
        assertTrue(entities.contains(entity));
        assertTrue(entities.remove(entity));
    }

    @Test
    void toStringDoesNotIncludeTheLazyNotification() {
        var notification = EmailNotificationEntity.builder().recipientEmail("excluded@example.com").build();
        var event = EmailDeliveryEventEntity.builder().notification(notification).eventType(EmailDeliveryEventType.PROCESSED).build();
        assertFalse(event.toString().contains("excluded@example.com"));
        assertFalse(event.toString().contains("notification="));
    }
}
