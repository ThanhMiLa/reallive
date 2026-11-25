package com.reallive.paymentservice.deposit.scheduler;

import com.reallive.paymentservice.client.vnpay.VnpayQueryDrAdapter;
import com.reallive.paymentservice.deposit.configuration.VnpayProperties;
import com.reallive.paymentservice.deposit.entity.PaymentTransactionEntity;
import com.reallive.paymentservice.deposit.entity.TransactionStatus;
import com.reallive.paymentservice.deposit.repository.PaymentTransactionRepository;
import com.reallive.paymentservice.deposit.service.PaymentSettlementService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@ConditionalOnProperty(prefix = "vnpay.reconcile", name = "enabled", havingValue = "true", matchIfMissing = false)
public class VnpayReconciliationScheduler {

    private static final Cursor START = new Cursor(LocalDateTime.of(1970, 1, 1, 0, 0), 0);

    PaymentTransactionRepository paymentRepository;
    VnpayQueryDrAdapter queryDrAdapter;
    PaymentSettlementService settlementService;
    VnpayProperties properties;
    Clock paymentClock;

    @NonFinal
    Cursor cursor = START;

    @Scheduled(fixedDelayString = "${vnpay.reconcile.fixed-delay-ms}")
    public void reconcile() {
        int batchSize = properties.reconcile().batchSize();
        LocalDateTime cutoff = LocalDateTime.now(paymentClock).minusMinutes(properties.reconcile().minAgeMinutes());
        List<PaymentTransactionEntity> payments = paymentRepository.findForReconciliation(TransactionStatus.PENDING,
                cutoff, cursor.createdAt(), cursor.orderCode(), PageRequest.ofSize(batchSize));
        if (payments.isEmpty() && !cursor.equals(START)) {
            payments = paymentRepository.findForReconciliation(TransactionStatus.PENDING, cutoff,
                    START.createdAt(), START.orderCode(), PageRequest.ofSize(batchSize));
        }

        for (PaymentTransactionEntity payment : payments) {
            try {
                queryDrAdapter.query(payment)
                        .ifPresent(result -> settlementService.settleFromQueryDr(payment.getOrderCode(), result));
            } catch (RuntimeException exception) {
                log.warn("VNPAY reconciliation failed for order {} ({})",
                        payment.getOrderCode(), exception.getClass().getSimpleName());
            }
        }

        // Advance even when older orders stay pending, so they cannot starve the rest of the queue.
        if (payments.size() == batchSize) {
            PaymentTransactionEntity last = payments.getLast();
            cursor = new Cursor(last.getCreatedAt(), last.getOrderCode());
        } else {
            cursor = START;
        }
    }

    private record Cursor(
            LocalDateTime createdAt,
            long orderCode
    ) {}
}
