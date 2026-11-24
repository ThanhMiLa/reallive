package com.reallive.paymentservice.wallet.repository;

import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserWalletRepository extends JpaRepository<UserWalletEntity, UUID> {

    Optional<UserWalletEntity> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    @Modifying
    @Query(value = """
            INSERT INTO {h-schema}user_wallets (user_id)
            VALUES (:userId)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(UUID userId);

}
