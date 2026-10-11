package com.reallive.paymentservice.wallet.service.impl;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import com.reallive.paymentservice.wallet.dto.response.WalletCreationResponse;
import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import com.reallive.paymentservice.wallet.mapper.WalletMapper;
import com.reallive.paymentservice.wallet.repository.UserWalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WalletServiceImplTest {

    private UserWalletRepository repository;
    private WalletMapper mapper;
    private WalletServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(UserWalletRepository.class);
        mapper = mock(WalletMapper.class);
        service = new WalletServiceImpl(repository, mapper);
    }

    @Test
    void createsZeroBalanceWalletForRequestedUserAndMapsPersistedEntity() {
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        WalletCreationResponse response = WalletCreationResponse.builder()
                .id(walletId).userId(userId).balance(0L).build();
        when(repository.save(any(UserWalletEntity.class))).thenAnswer(invocation -> {
            UserWalletEntity wallet = invocation.getArgument(0);
            wallet.setId(walletId);
            return wallet;
        });
        when(mapper.toWalletCreationResponse(any())).thenReturn(response);

        assertSame(response, service.createWallet(userId));

        ArgumentCaptor<UserWalletEntity> saved = ArgumentCaptor.forClass(UserWalletEntity.class);
        var order = inOrder(repository, mapper);
        order.verify(repository).existsByUserId(userId);
        order.verify(repository).save(saved.capture());
        order.verify(mapper).toWalletCreationResponse(saved.getValue());
        assertEquals(userId, saved.getValue().getUserId());
        assertEquals(0L, saved.getValue().getBalance());
        assertEquals(walletId, saved.getValue().getId());
        verifyNoMoreInteractions(repository, mapper);
    }

    @Test
    void existingWalletReturnsConflictWithoutSavingOrMapping() {
        UUID userId = UUID.randomUUID();
        when(repository.existsByUserId(userId)).thenReturn(true);

        AppException failure = assertThrows(AppException.class, () -> service.createWallet(userId));

        assertEquals(ErrorCode.WALLET_ALREADY_EXISTS, failure.getErrorCode());
        verify(repository).existsByUserId(userId);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(mapper);
    }

    @Test
    void lookupFailureDoesNotAttemptInsert() {
        UUID userId = UUID.randomUUID();
        var failure = new DataAccessResourceFailureException("Connection unavailable");
        when(repository.existsByUserId(userId)).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.createWallet(userId)));
        verify(repository, never()).save(any());
        verifyNoInteractions(mapper);
    }

    @Test
    void insertConstraintFailureDoesNotProduceSuccessResponse() {
        var failure = new DataIntegrityViolationException("Duplicate user_id");
        when(repository.save(any())).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.createWallet(UUID.randomUUID())));
        verifyNoInteractions(mapper);
    }

    @Test
    void mappingFailurePropagatesOutOfTransactionalMethod() {
        var failure = new IllegalStateException("Mapping unavailable");
        when(mapper.toWalletCreationResponse(any())).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.createWallet(UUID.randomUUID())));
        verify(repository).save(any());
    }
}
