package com.reallive.paymentservice.wallet.service.impl;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import com.reallive.paymentservice.wallet.repository.UserWalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WalletServiceImplTest {

    private final UserWalletRepository repository = mock(UserWalletRepository.class);
    private final WalletServiceImpl service = new WalletServiceImpl(repository);

    @Test
    void insertFailurePropagatesWithoutReadingBalance() {
        UUID userId = UUID.randomUUID();
        var failure = new DataAccessResourceFailureException("Database unavailable");
        doThrow(failure).when(repository).insertIfAbsent(userId);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> service.createWallet(userId)));
        verify(repository, never()).findByUserId(userId);
    }

    @Test
    void missingWalletAfterInsertIsReportedAsUnavailable() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());

        var failure = assertThrows(AppException.class, () -> service.createWallet(userId));
        assertEquals(ErrorCode.PAYMENT_DATA_UNAVAILABLE, failure.getErrorCode());
    }
}
