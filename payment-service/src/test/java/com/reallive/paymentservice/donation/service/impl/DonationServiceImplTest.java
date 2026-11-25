package com.reallive.paymentservice.donation.service.impl;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import com.reallive.paymentservice.donation.client.stream.StreamDonationContextApiClient;
import com.reallive.paymentservice.donation.client.stream.StreamDonationContextResponse;
import com.reallive.paymentservice.donation.dto.request.DonateGiftRequest;
import com.reallive.paymentservice.donation.entity.DonationEntity;
import com.reallive.paymentservice.donation.entity.VirtualGiftEntity;
import com.reallive.paymentservice.donation.repository.DonationRepository;
import com.reallive.paymentservice.donation.repository.VirtualGiftRepository;
import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import com.reallive.paymentservice.wallet.entity.WalletTransactionEntity;
import com.reallive.paymentservice.wallet.repository.StreamerWalletRepository;
import com.reallive.paymentservice.wallet.repository.UserWalletRepository;
import com.reallive.paymentservice.wallet.repository.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DonationServiceImplTest {

    private final DonationRepository donations = mock(DonationRepository.class);
    private final VirtualGiftRepository gifts = mock(VirtualGiftRepository.class);
    private final UserWalletRepository wallets = mock(UserWalletRepository.class);
    private final WalletTransactionRepository transactions = mock(WalletTransactionRepository.class);
    private final StreamerWalletRepository streamerWallets = mock(StreamerWalletRepository.class);
    private final StreamDonationContextApiClient streams = mock(StreamDonationContextApiClient.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
    private final DonationServiceImpl service = new DonationServiceImpl(
            donations, gifts, wallets, transactions, streamerWallets, streams, transactionManager, clock);
    private final UUID donor = UUID.randomUUID();
    private final UUID streamer = UUID.randomUUID();
    private final DonateGiftRequest request = new DonateGiftRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final LocalDateTime now = LocalDateTime.now(clock);
    private VirtualGiftEntity gift;

    @BeforeEach
    void setUp() {
        gift = VirtualGiftEntity.builder().id(request.giftId()).name("Rose").imageUrl("/gifts/rose.svg")
                .coinPrice(1).status(VirtualGiftEntity.Status.ACTIVE).build();
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(streams.getDonationContext(request.streamId())).thenReturn(new StreamDonationContextResponse(
                request.streamId(), UUID.randomUUID(), streamer, UUID.randomUUID(), "PUBLIC", "LIVE"));
        when(gifts.findById(request.giftId())).thenReturn(Optional.of(gift));
        when(donations.saveAndFlush(any())).thenAnswer(invocation -> {
            DonationEntity donation = invocation.getArgument(0);
            donation.setId(UUID.randomUUID());
            return donation;
        });
        when(wallets.debitBalanceByUserId(donor, 1, now)).thenReturn(1);
        when(wallets.findByUserId(donor)).thenReturn(Optional.of(UserWalletEntity.builder()
                .id(UUID.randomUUID()).userId(donor).balance(0L).build()));
        when(streamerWallets.creditAvailableBalanceByStreamerId(streamer, 1, now)).thenReturn(1);
    }

    @Test
    void savesDonationBeforeDebitAndCreditsFullPriceInOneTransaction() {
        var result = service.donate(donor, request);
        assertEquals(1, result.coinPrice());
        assertEquals(0, result.remainingBalance());
        assertEquals(now, result.createdAt());
        var order = inOrder(streams, transactionManager, donations, wallets, transactions, streamerWallets);
        order.verify(streams).getDonationContext(request.streamId());
        order.verify(transactionManager).getTransaction(any());
        order.verify(donations).saveAndFlush(any());
        order.verify(wallets).debitBalanceByUserId(donor, 1, now);
        order.verify(wallets).findByUserId(donor);
        order.verify(transactions).save(any(WalletTransactionEntity.class));
        order.verify(streamerWallets).insertIfAbsent(streamer);
        order.verify(streamerWallets).creditAvailableBalanceByStreamerId(streamer, 1, now);
        order.verify(transactionManager).commit(any());
    }

    @Test
    void failedRecipientCreditRollsBack() {
        when(streamerWallets.creditAvailableBalanceByStreamerId(streamer, 1, now)).thenReturn(0);
        assertError(ErrorCode.PAYMENT_DATA_UNAVAILABLE);
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void zeroDebitDistinguishesMissingWalletAndInsufficientBalance() {
        when(wallets.debitBalanceByUserId(donor, 1, now)).thenReturn(0);
        assertError(ErrorCode.WALLET_NOT_FOUND);
        when(wallets.existsByUserId(donor)).thenReturn(true);
        assertError(ErrorCode.INSUFFICIENT_BALANCE);
        verifyNoInteractions(transactions, streamerWallets);
    }

    @Test
    void unrelatedIntegrityFailureBecomesPaymentDataUnavailable() {
        doThrow(new DataIntegrityViolationException("constraint")).when(donations).saveAndFlush(any());
        assertError(ErrorCode.PAYMENT_DATA_UNAVAILABLE);
        verify(transactionManager).rollback(any());
        verifyNoInteractions(transactions, streamerWallets);
    }

    @Test
    void duplicateInsertReturnsWinnerAfterRollbackWithoutDebit() {
        var existing = DonationEntity.builder().id(UUID.randomUUID()).donorUserId(donor)
                .streamId(request.streamId()).giftId(request.giftId()).coinPrice(1).createdAt(now).build();
        when(donations.findByDonorUserIdAndIdempotencyKey(donor, request.idempotencyKey()))
                .thenReturn(Optional.empty(), Optional.of(existing));
        doThrow(new DataIntegrityViolationException("duplicate")).when(donations).saveAndFlush(any());
        assertEquals(existing.getId(), service.donate(donor, request).donationId());
        var order = inOrder(transactionManager, donations);
        order.verify(transactionManager).rollback(any());
        order.verify(donations).findByDonorUserIdAndIdempotencyKey(donor, request.idempotencyKey());
        verify(wallets, never()).debitBalanceByUserId(any(), eq(1L), any());
    }

    @Test
    void replayUsesSnapshotPriceCurrentBalanceAndNoRemoteCallEvenForInactiveGift() {
        gift.setStatus(VirtualGiftEntity.Status.INACTIVE);
        gift.setCoinPrice(100);
        when(donations.findByDonorUserIdAndIdempotencyKey(donor, request.idempotencyKey()))
                .thenReturn(Optional.of(DonationEntity.builder().id(UUID.randomUUID()).donorUserId(donor)
                        .streamId(request.streamId()).giftId(request.giftId()).coinPrice(1).createdAt(now).build()));
        assertEquals(1, service.donate(donor, request).coinPrice());
        verifyNoInteractions(streams, transactionManager, transactions, streamerWallets);
    }

    @Test
    void duplicatePayloadConflictIsRejectedBeforeLoadingWalletOrStream() {
        when(donations.findByDonorUserIdAndIdempotencyKey(donor, request.idempotencyKey()))
                .thenReturn(Optional.of(DonationEntity.builder().streamId(UUID.randomUUID()).giftId(request.giftId()).build()));
        assertError(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        verifyNoInteractions(streams, wallets, transactionManager);
    }

    private void assertError(ErrorCode expected) {
        assertEquals(expected, assertThrows(AppException.class, () -> service.donate(donor, request)).getErrorCode());
    }
}
