package com.reallive.paymentservice.wallet.service.impl;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import com.reallive.paymentservice.wallet.client.streamer.StreamerApiClient;
import com.reallive.paymentservice.wallet.dto.response.StreamerWalletResponse;
import com.reallive.paymentservice.wallet.entity.StreamerWalletEntity;
import com.reallive.paymentservice.wallet.repository.StreamerWalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StreamerWalletServiceImplTest {

    private final StreamerWalletRepository repository = mock(StreamerWalletRepository.class);
    private final StreamerApiClient streamerApiClient = mock(StreamerApiClient.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final StreamerWalletServiceImpl service =
            new StreamerWalletServiceImpl(repository, streamerApiClient, transactionManager);

    @Test
    void createWalletResolvesActiveStreamerOutsideTransactionBeforeIdempotentInsert() {
        UUID userId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(streamerApiClient.getActiveStreamerIdByUserId(userId)).thenReturn(streamerId);
        when(transactionManager.getTransaction(any())).thenReturn(txStatus);
        when(repository.findByStreamerId(streamerId)).thenReturn(Optional.of(
                StreamerWalletEntity.builder().id(walletId).streamerId(streamerId).availableBalance(350L).build()));

        StreamerWalletResponse response = service.createWallet(userId);

        assertEquals(new StreamerWalletResponse(walletId, streamerId, 350L), response);
        InOrder order = inOrder(streamerApiClient, transactionManager, repository);
        order.verify(streamerApiClient).getActiveStreamerIdByUserId(userId);
        order.verify(transactionManager).getTransaction(any());
        order.verify(repository).insertIfAbsent(streamerId);
        order.verify(repository).findByStreamerId(streamerId);
        order.verify(transactionManager).commit(txStatus);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"STREAMER_NOT_FOUND", "STREAMER_NOT_ACTIVE", "USER_SERVICE_UNAVAILABLE"})
    void createWalletPropagatesUpstreamFailureWithoutStartingTransaction(ErrorCode errorCode) {
        UUID userId = UUID.randomUUID();
        when(streamerApiClient.getActiveStreamerIdByUserId(userId)).thenThrow(new AppException(errorCode));

        AppException exception = assertThrows(AppException.class, () -> service.createWallet(userId));

        assertEquals(errorCode, exception.getErrorCode());
        verifyNoInteractions(transactionManager, repository);
    }

    @Test
    void createWalletRollsBackAndPropagatesInsertFailureWithoutReadingWallet() {
        UUID userId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        TransactionStatus txStatus = mock(TransactionStatus.class);
        var failure = new DataAccessResourceFailureException("Database unavailable");
        when(streamerApiClient.getActiveStreamerIdByUserId(userId)).thenReturn(streamerId);
        when(transactionManager.getTransaction(any())).thenReturn(txStatus);
        doThrow(failure).when(repository).insertIfAbsent(streamerId);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.createWallet(userId)));
        verify(transactionManager).rollback(txStatus);
        verify(repository, never()).findByStreamerId(streamerId);
    }

    @Test
    void createWalletRollsBackAndReportsMissingWalletAfterInsertAsUnavailable() {
        UUID userId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(streamerApiClient.getActiveStreamerIdByUserId(userId)).thenReturn(streamerId);
        when(transactionManager.getTransaction(any())).thenReturn(txStatus);
        when(repository.findByStreamerId(streamerId)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> service.createWallet(userId));

        assertEquals(ErrorCode.PAYMENT_DATA_UNAVAILABLE, exception.getErrorCode());
        verify(transactionManager).rollback(txStatus);
    }

    @Test
    void getBalanceResolvesStreamerAndReturnsExistingWalletWithoutOpeningTransaction() {
        UUID userId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        when(streamerApiClient.getActiveStreamerIdByUserId(userId)).thenReturn(streamerId);
        when(repository.findByStreamerId(streamerId)).thenReturn(Optional.of(
                StreamerWalletEntity.builder().id(walletId).streamerId(streamerId).availableBalance(900L).build()));

        StreamerWalletResponse response = service.getBalance(userId);

        assertEquals(new StreamerWalletResponse(walletId, streamerId, 900L), response);
        verifyNoInteractions(transactionManager);
    }

    @Test
    void getBalanceThrowsStreamerWalletNotFoundWhenWalletDoesNotExist() {
        UUID userId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        when(streamerApiClient.getActiveStreamerIdByUserId(userId)).thenReturn(streamerId);
        when(repository.findByStreamerId(streamerId)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> service.getBalance(userId));

        assertEquals(ErrorCode.STREAMER_WALLET_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(transactionManager);
    }

    @Test
    void getBalancePropagatesUpstreamFailureWithoutReadingRepository() {
        UUID userId = UUID.randomUUID();
        when(streamerApiClient.getActiveStreamerIdByUserId(userId))
                .thenThrow(new AppException(ErrorCode.STREAMER_NOT_ACTIVE));

        AppException exception = assertThrows(AppException.class, () -> service.getBalance(userId));

        assertEquals(ErrorCode.STREAMER_NOT_ACTIVE, exception.getErrorCode());
        verifyNoInteractions(transactionManager, repository);
    }

    @Test
    void ensureStreamerWalletInsertsIdempotentlyAndReturnsResponse() {
        UUID streamerId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        when(repository.findByStreamerId(streamerId)).thenReturn(Optional.of(
                StreamerWalletEntity.builder().id(walletId).streamerId(streamerId).availableBalance(400L).build()));

        StreamerWalletResponse response = service.ensureStreamerWallet(streamerId);

        assertEquals(new StreamerWalletResponse(walletId, streamerId, 400L), response);
        verify(repository).insertIfAbsent(streamerId);
        verify(repository).findByStreamerId(streamerId);
    }

    @Test
    void ensureStreamerWalletThrowsPaymentDataUnavailableWhenMissingAfterInsert() {
        UUID streamerId = UUID.randomUUID();
        when(repository.findByStreamerId(streamerId)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class,
                () -> service.ensureStreamerWallet(streamerId));

        assertEquals(ErrorCode.PAYMENT_DATA_UNAVAILABLE, exception.getErrorCode());
    }
}
