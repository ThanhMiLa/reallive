package com.reallive.userservice.auth.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;

@Data
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "invalidated_tokens",
        indexes = @Index(name = "idx_invalidated_tokens_expiry_time", columnList = "expiry_time"))
public class InvalidatedTokenEntity {

    @Id
    @Column(name = "jti", nullable = false, updatable = false)
    UUID jti;

    @Column(name = "expiry_time", nullable = false)
    LocalDateTime expiryTime;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    LocalDateTime createdAt;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InvalidatedTokenEntity entity)) {
            return false;
        }
        return getJti() != null && getJti().equals(entity.getJti());
    }

    @Override
    public final int hashCode() {
        return InvalidatedTokenEntity.class.hashCode();
    }
}
