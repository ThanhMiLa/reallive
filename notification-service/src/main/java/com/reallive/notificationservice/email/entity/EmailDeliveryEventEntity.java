package com.reallive.notificationservice.email.entity;

import com.reallive.notificationservice.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "email_delivery_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_email_delivery_events_sg_event_id", columnNames = "sg_event_id"),
        indexes = @Index(name = "idx_email_delivery_events_notification_timestamp",
                columnList = "notification_id, provider_timestamp"))
public class EmailDeliveryEventEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_email_delivery_events_notification"))
    @ToString.Exclude
    EmailNotificationEntity notification;

    @Column(name = "sg_event_id", columnDefinition = "TEXT", updatable = false)
    String sgEventId;

    @Column(name = "sg_message_id", columnDefinition = "TEXT")
    String sgMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    EmailDeliveryEventType eventType;

    @Column(name = "provider_timestamp", nullable = false)
    Instant providerTimestamp;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    Instant receivedAt;

    @Column(name = "response", columnDefinition = "TEXT")
    String response;

    @Column(name = "reason", columnDefinition = "TEXT")
    String reason;

    @Column(name = "smtp_status", length = 50)
    String smtpStatus;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EmailDeliveryEventEntity entity)) {
            return false;
        }
        return getId() != null && getId().equals(entity.getId());
    }

    @Override
    public final int hashCode() {
        return EmailDeliveryEventEntity.class.hashCode();
    }
}
