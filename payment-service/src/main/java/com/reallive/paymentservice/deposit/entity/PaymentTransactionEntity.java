package com.reallive.paymentservice.deposit.entity;

import com.reallive.paymentservice.common.entity.BaseEntity;
import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "payment_transactions")
public class PaymentTransactionEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    UserWalletEntity wallet;

    @Column(nullable = false, updatable = false)
    long orderCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    Provider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 50)
    CoinDepositPackage packageCode;

    @Column(nullable = false, updatable = false)
    long amount;

    @Column(nullable = false, updatable = false)
    long coinAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    TransactionStatus status;

    @Column(nullable = false, updatable = false, length = 14)
    String providerCreateDate;

    @Column(nullable = false, updatable = false)
    LocalDateTime expiresAt;

    @Column(length = 64)
    String providerTransactionNo;

    @Column(length = 10)
    String providerResponseCode;

    @Column(length = 10)
    String providerTransactionStatus;

    LocalDateTime paidAt;

    public void settle(TransactionStatus status, String transactionNo, String responseCode,
                       String transactionStatus, LocalDateTime settledAt) {
        this.status = status;
        this.providerTransactionNo = transactionNo;
        this.providerResponseCode = responseCode;
        this.providerTransactionStatus = transactionStatus;
        if (status == TransactionStatus.PAID) {
            this.paidAt = settledAt;
        }
    }

    public enum Provider {
        VNPAY
    }
}
