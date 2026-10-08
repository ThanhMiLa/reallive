package com.reallive.userservice.streamer.entity;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.common.entity.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "streamers",
        uniqueConstraints = @UniqueConstraint(name = "uk_streamers_user_id", columnNames = "user_id"),
        indexes = {
            @Index(name = "idx_streamers_status", columnList = "status"),
            @Index(name = "idx_streamers_suspended_until", columnList = "suspended_until"),
            @Index(name = "idx_streamers_status_suspended_until", columnList = "status, suspended_until")
        })
public class StreamerEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_streamers_user"))
    @ToString.Exclude
    UserEntity user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    StreamerStatus status;

    @Column(name = "verified_at")
    LocalDateTime verifiedAt;

    @Column(name = "suspended_at")
    LocalDateTime suspendedAt;

    @Column(name = "suspended_until")
    LocalDateTime suspendedUntil;

    @Column(name = "suspension_reason", length = 500)
    String suspensionReason;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StreamerEntity entity)) {
            return false;
        }
        return getId() != null && getId().equals(entity.getId());
    }

    @Override
    public final int hashCode() {
        return StreamerEntity.class.hashCode();
    }
}
