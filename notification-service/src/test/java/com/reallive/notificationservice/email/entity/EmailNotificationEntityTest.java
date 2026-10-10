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

class EmailNotificationEntityTest {

    @Test
    void sameIdDefinesEqualityRegardlessOfAuditAndSubmissionFields() {
        UUID id = UUID.randomUUID();
        var first = EmailNotificationEntity.builder().id(id).status(EmailNotificationStatus.PENDING).build();
        var second = EmailNotificationEntity.builder().id(id).status(EmailNotificationStatus.ACCEPTED).build();
        first.setCreatedAt(Instant.parse("2026-10-10T00:00:00Z"));
        second.setUpdatedAt(Instant.parse("2026-10-10T01:00:00Z"));
        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void distinctAndTransientEntitiesAreNotEqual() {
        var first = new EmailNotificationEntity();
        var second = new EmailNotificationEntity();
        assertEquals(first, first);
        assertNotEquals(first, second);
        first.setId(UUID.randomUUID());
        second.setId(UUID.randomUUID());
        assertNotEquals(first, second);
        assertNotEquals(first, null);
        assertNotEquals(first, new EmailDeliveryEventEntity());
    }

    @Test
    void hashSetMembershipSurvivesIdentifierAndAuditUpdates() {
        var entity = new EmailNotificationEntity();
        Set<EmailNotificationEntity> entities = new HashSet<>();
        entities.add(entity);
        entity.setId(UUID.randomUUID());
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setStatus(EmailNotificationStatus.ACCEPTED);
        assertTrue(entities.contains(entity));
        assertTrue(entities.remove(entity));
        assertFalse(entities.contains(entity));
    }
}
