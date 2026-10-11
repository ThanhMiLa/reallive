package com.reallive.paymentservice.wallet.entity;

import com.reallive.paymentservice.common.entity.BaseEntity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "user_wallets", uniqueConstraints =
        @UniqueConstraint(name = "uk_user_wallets_user_id", columnNames = "user_id"))
public class UserWalletEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    UUID userId;

    @Builder.Default
    @Column(name = "balance", nullable = false)
    Long balance = 0L;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UserWalletEntity entity)) {
            return false;
        }
        return getId() != null && getId().equals(entity.getId());
    }

    @Override
    public final int hashCode() {
        return UserWalletEntity.class.hashCode();
    }
}
