package com.reallive.paymentservice.deposit.service.impl;

import com.reallive.paymentservice.deposit.adapter.vnpay.VnpayPaymentResult;
import com.reallive.paymentservice.deposit.configuration.VnpayProperties;
import com.reallive.paymentservice.deposit.dto.response.VnpayIpnResponse;
import com.reallive.paymentservice.deposit.entity.PaymentTransactionEntity;
import com.reallive.paymentservice.deposit.entity.TransactionStatus;
import com.reallive.paymentservice.deposit.repository.PaymentTransactionRepository;
import com.reallive.paymentservice.deposit.service.PaymentSettlementService;
import com.reallive.paymentservice.wallet.entity.WalletTransactionEntity;
import com.reallive.paymentservice.wallet.repository.UserWalletRepository;
import com.reallive.paymentservice.wallet.repository.WalletTransactionRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PaymentSettlementServiceImpl implements PaymentSettlementService {

    PaymentTransactionRepository paymentRepository;
    WalletTransactionRepository walletTransactionRepository;
    UserWalletRepository walletRepository;
    VnpayProperties properties;
    Clock paymentClock;

    @Override
    @Transactional
    public VnpayIpnResponse settleFromIpn(long orderCode, long amount, VnpayPaymentResult result) {
        PaymentTransactionEntity payment = paymentRepository.findByOrderCodeForUpdate(orderCode).orElse(null);
        if (payment == null) {
            return VnpayIpnResponse.ORDER_NOT_FOUND;
        }
        if (payment.getAmount() * 100L != amount) {
            return VnpayIpnResponse.INVALID_AMOUNT;
        }
        if (payment.getStatus() != TransactionStatus.PENDING) {
            return VnpayIpnResponse.ALREADY_CONFIRMED;
        }
        TransactionStatus status = switch (result.responseCode()) {
            case "00" -> "00".equals(result.transactionStatus()) ? TransactionStatus.PAID : TransactionStatus.FAILED;
            case "24" -> TransactionStatus.CANCELLED;
            default -> TransactionStatus.FAILED;
        };
        settle(payment, result, status, LocalDateTime.now(paymentClock));
        return VnpayIpnResponse.CONFIRMED;
    }

    @Override
    @Transactional
    public void settleFromQueryDr(long orderCode, VnpayPaymentResult result) {
        PaymentTransactionEntity payment = paymentRepository.findByOrderCodeForUpdate(orderCode).orElse(null);
        if (payment == null || payment.getStatus() != TransactionStatus.PENDING) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(paymentClock);
        boolean found = "00".equals(result.responseCode());
        if (found && "00".equals(result.transactionStatus())) {
            settle(payment, result, TransactionStatus.PAID, now);
        } else if (found && "02".equals(result.transactionStatus())) {
            settle(payment, result, TransactionStatus.FAILED, now);
        } else if (("91".equals(result.responseCode()) || (found && "01".equals(result.transactionStatus())))
                && now.isAfter(payment.getExpiresAt().plusMinutes(properties.reconcile().expireBufferMinutes()))) {
            settle(payment, result, TransactionStatus.CANCELLED, now);
        }
    }

    private void settle(PaymentTransactionEntity payment, VnpayPaymentResult result, TransactionStatus status,
                        LocalDateTime now) {
        String transactionNo = result.transactionNo() == null ? "" : result.transactionNo().strip();
        payment.settle(status, transactionNo.isEmpty() || "0".equals(transactionNo) ? null : transactionNo,
                result.responseCode(), result.transactionStatus(), now);

        if (status == TransactionStatus.PAID) {
            UUID walletId = payment.getWallet().getId();
            walletTransactionRepository.save(WalletTransactionEntity.builder()
                    .walletId(walletId)
                    .type(WalletTransactionEntity.Type.DEPOSIT)
                    .amount(payment.getCoinAmount())
                    .referenceId(payment.getId())
                    .createdAt(now)
                    .build());
            if (walletRepository.creditBalance(walletId, payment.getCoinAmount(), now) != 1) {
                throw new IllegalStateException("Deposit credit did not update exactly one wallet");
            }
        }
    }
}
