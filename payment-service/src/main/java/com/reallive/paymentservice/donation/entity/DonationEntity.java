package com.reallive.paymentservice.donation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "donations", uniqueConstraints =
        @UniqueConstraint(name = "uk_donations_donor_idempotency", columnNames = {"donor_user_id", "idempotency_key"}))
public class DonationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(nullable = false, updatable = false)
    UUID donorUserId;

    @Column(nullable = false, updatable = false)
    UUID streamerId;

    @Column(nullable = false, updatable = false)
    UUID channelId;

    @Column(nullable = false, updatable = false)
    UUID streamId;

    @Column(nullable = false, updatable = false)
    UUID giftId;

    @Column(nullable = false, updatable = false)
    UUID idempotencyKey;

    @Column(nullable = false, updatable = false)
    long coinPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    Status status;

    @Column(nullable = false, updatable = false)
    LocalDateTime createdAt;

    public enum Status {
        COMPLETED, REFUNDED
    }
}
