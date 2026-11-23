package com.reallive.paymentservice.deposit.scheduler;

import com.reallive.paymentservice.deposit.adapter.vnpay.VnpayPaymentResult;
import com.reallive.paymentservice.deposit.adapter.vnpay.VnpayQueryDrAdapter;
import com.reallive.paymentservice.deposit.configuration.VnpayProperties;
import com.reallive.paymentservice.deposit.configuration.VnpaySchedulingConfiguration;
import com.reallive.paymentservice.deposit.entity.DepositStatus;
import com.reallive.paymentservice.deposit.entity.PaymentTransactionEntity;
import com.reallive.paymentservice.deposit.repository.PaymentTransactionRepository;
import com.reallive.paymentservice.deposit.service.PaymentSettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class VnpayReconciliationSchedulerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T01:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime START = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime CUTOFF = LocalDateTime.now(CLOCK).minusMinutes(3);

    private final PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
    private final VnpayQueryDrAdapter adapter = mock(VnpayQueryDrAdapter.class);
    private final PaymentSettlementService settlement = mock(PaymentSettlementService.class);
    private final VnpayProperties properties = new VnpayProperties(null, null, null, null, null,
            "2.1.0", "VND", "vn", "other", 15,
            new VnpayProperties.Reconcile(true, 300_000, 3, 10, 2, 5_000, "127.0.0.1"));
    private final VnpayReconciliationScheduler scheduler =
            new VnpayReconciliationScheduler(repository, adapter, settlement, properties, CLOCK);

    @Test
    void schedulingIsRegisteredOnlyWhenExplicitlyEnabled() {
        var contextRunner = new ApplicationContextRunner().withUserConfiguration(VnpaySchedulingConfiguration.class);
        String processor = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(processor));
        contextRunner.withPropertyValues("vnpay.reconcile.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(processor));
        contextRunner.withPropertyValues("vnpay.reconcile.enabled=true")
                .run(context -> assertThat(context).hasBean(processor));
    }

    @Test
    void verificationAndNetworkFailuresKeepPendingAndDoNotStarveLaterOrders() {
        LocalDateTime createdAt = CUTOFF.minusMinutes(1);
        var first = payment(1, createdAt);
        var second = payment(2, createdAt);
        var third = payment(3, CUTOFF);
        var result = new VnpayPaymentResult("00", "00", "123");
        when(repository.findForReconciliation(DepositStatus.PENDING, CUTOFF, START, 0, PageRequest.ofSize(2)))
                .thenReturn(List.of(first, second));
        when(repository.findForReconciliation(DepositStatus.PENDING, CUTOFF, createdAt, 2, PageRequest.ofSize(2)))
                .thenReturn(List.of(third));
        when(adapter.query(first)).thenReturn(Optional.empty());
        when(adapter.query(second)).thenThrow(new IllegalStateException("simulated network outage"));
        when(adapter.query(third)).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.of(result);
        });

        scheduler.reconcile();
        verifyNoInteractions(settlement);
        scheduler.reconcile();
        verify(settlement).settleFromQueryDr(3, result);
        verifyNoMoreInteractions(settlement);
    }

    @Test
    void skipsProviderWhenNoPendingOrdersMatchAgeCutoff() {
        when(repository.findForReconciliation(DepositStatus.PENDING, CUTOFF, START, 0, PageRequest.ofSize(2)))
                .thenReturn(List.of());
        scheduler.reconcile();
        verifyNoInteractions(adapter, settlement);
    }

    private static PaymentTransactionEntity payment(long orderCode, LocalDateTime createdAt) {
        PaymentTransactionEntity payment = PaymentTransactionEntity.builder().orderCode(orderCode).build();
        payment.setCreatedAt(createdAt);
        return payment;
    }
}
