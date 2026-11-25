package com.reallive.paymentservice.wallet.entity;

import com.reallive.paymentservice.common.entity.BaseEntity;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.UUID;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(
        name = "streamer_wallets",
        uniqueConstraints = @UniqueConstraint(name = "uk_streamer_wallets_streamer_id", columnNames = "streamer_id")
)
public class StreamerWalletEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    UUID id;

    @Column(name = "streamer_id", nullable = false, updatable = false)
    UUID streamerId;

    @Builder.Default
    @Column(name = "available_balance", nullable = false)
    Long availableBalance = 0L;
}
