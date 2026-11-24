package com.reallive.paymentservice.deposit.repository;

import com.reallive.paymentservice.deposit.entity.PaymentTransactionEntity;
import com.reallive.paymentservice.deposit.entity.TransactionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransactionEntity, UUID> {

    @Query("""
            SELECT p
            FROM PaymentTransactionEntity p
            JOIN FETCH p.wallet w
            WHERE p.orderCode = :orderCode
              AND w.userId = :userId
            """)
    Optional<PaymentTransactionEntity> findOwnedOrder(long orderCode, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p
            FROM PaymentTransactionEntity p
            WHERE p.orderCode = :orderCode
            """)
    Optional<PaymentTransactionEntity> findByOrderCodeForUpdate(long orderCode);

    @Query("""
            SELECT p
            FROM PaymentTransactionEntity p
            WHERE p.status = :status
              AND p.createdAt <= :cutoff
              AND (p.createdAt > :afterCreatedAt
                   OR (p.createdAt = :afterCreatedAt AND p.orderCode > :afterOrderCode))
            ORDER BY p.createdAt, p.orderCode
            """)
    List<PaymentTransactionEntity> findForReconciliation(TransactionStatus status, LocalDateTime cutoff,
                                                        LocalDateTime afterCreatedAt, long afterOrderCode,
                                                        Pageable pageable);
}
