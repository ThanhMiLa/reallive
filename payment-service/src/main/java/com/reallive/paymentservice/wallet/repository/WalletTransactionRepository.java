package com.reallive.paymentservice.wallet.repository;

import com.reallive.paymentservice.wallet.entity.WalletTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WalletTransactionRepository extends JpaRepository<WalletTransactionEntity, UUID> {
}
