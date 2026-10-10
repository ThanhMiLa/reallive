package com.reallive.notificationservice.email.entity;

import com.reallive.notificationservice.email.dto.message.EmailType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "email_notifications",
        uniqueConstraints = @UniqueConstraint(name = "uk_email_notifications_event_id", columnNames = "event_id"),
        indexes = @Index(name = "idx_email_notifications_user_created_at", columnList = "user_id, created_at"))
public class EmailNotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    UUID eventId;

    @Column(name = "user_id", nullable = false, updatable = false)
    UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "email_type", nullable = false, length = 50)
    EmailType emailType;

    @Column(name = "recipient_email", nullable = false, length = 255)
    String recipientEmail;

    @Column(name = "template_id", length = 255)
    String templateId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    EmailNotificationStatus status = EmailNotificationStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    int attemptCount;

    @Column(name = "last_http_status")
    Short lastHttpStatus;

    @Column(name = "last_error", columnDefinition = "TEXT")
    String lastError;

    @Column(name = "provider_message_id", columnDefinition = "TEXT")
    String providerMessageId;

    @Column(name = "issued_at", nullable = false, updatable = false)
    Instant issuedAt;

    @Column(name = "expires_at")
    Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EmailNotificationEntity entity)) {
            return false;
        }
        return getId() != null && getId().equals(entity.getId());
    }

    @Override
    public final int hashCode() {
        return EmailNotificationEntity.class.hashCode();
    }
}
