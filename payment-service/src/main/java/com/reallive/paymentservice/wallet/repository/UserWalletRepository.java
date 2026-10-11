package com.reallive.paymentservice.wallet.repository;

import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserWalletRepository extends JpaRepository<UserWalletEntity, UUID> {

    Optional<UserWalletEntity> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);
}
