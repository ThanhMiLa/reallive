package com.reallive.paymentservice.wallet.repository;

import com.reallive.paymentservice.wallet.entity.StreamerWalletEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface StreamerWalletRepository extends JpaRepository<StreamerWalletEntity, UUID> {

    Optional<StreamerWalletEntity> findByStreamerId(UUID streamerId);

    @Modifying
    @Query(value = """
            INSERT INTO {h-schema}streamer_wallets (streamer_id)
            VALUES (:streamerId)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(UUID streamerId);

    @Modifying
    @Query("""
            UPDATE StreamerWalletEntity w
            SET w.availableBalance = w.availableBalance + :amount,
                w.updatedAt = :updatedAt
            WHERE w.id = :walletId
            """)
    int creditAvailableBalance(UUID walletId, long amount, LocalDateTime updatedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StreamerWalletEntity w
            SET w.availableBalance = w.availableBalance + :amount,
                w.updatedAt = :updatedAt
            WHERE w.streamerId = :streamerId
            """)
    int creditAvailableBalanceByStreamerId(UUID streamerId, long amount, LocalDateTime updatedAt);
}
