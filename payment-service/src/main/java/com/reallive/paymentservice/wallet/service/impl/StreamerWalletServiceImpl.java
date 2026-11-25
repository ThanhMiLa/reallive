package com.reallive.paymentservice.wallet.service.impl;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import com.reallive.paymentservice.wallet.client.streamer.StreamerApiClient;
import com.reallive.paymentservice.wallet.dto.response.StreamerWalletResponse;
import com.reallive.paymentservice.wallet.entity.StreamerWalletEntity;
import com.reallive.paymentservice.wallet.repository.StreamerWalletRepository;
import com.reallive.paymentservice.wallet.service.StreamerWalletService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamerWalletServiceImpl implements StreamerWalletService {

    StreamerWalletRepository streamerWalletRepository;
    StreamerApiClient streamerApiClient;
    PlatformTransactionManager transactionManager;

    @Override
    public StreamerWalletResponse createWallet(UUID userId) {
        UUID streamerId = streamerApiClient.getActiveStreamerIdByUserId(userId);
        return new TransactionTemplate(transactionManager)
                .execute(status -> ensureWalletInTransaction(streamerId));
    }

    @Override
    public StreamerWalletResponse getBalance(UUID userId) {
        UUID streamerId = streamerApiClient.getActiveStreamerIdByUserId(userId);
        StreamerWalletEntity wallet = streamerWalletRepository.findByStreamerId(streamerId)
                .orElseThrow(() -> new AppException(ErrorCode.STREAMER_WALLET_NOT_FOUND));
        return toResponse(wallet);
    }

    @Override
    @Transactional
    public StreamerWalletResponse ensureStreamerWallet(UUID streamerId) {
        return ensureWalletInTransaction(streamerId);
    }

    private StreamerWalletResponse ensureWalletInTransaction(UUID streamerId) {
        streamerWalletRepository.insertIfAbsent(streamerId);
        StreamerWalletEntity wallet = streamerWalletRepository.findByStreamerId(streamerId)
                .orElseThrow(() -> new AppException(ErrorCode.PAYMENT_DATA_UNAVAILABLE));
        return toResponse(wallet);
    }

    private static StreamerWalletResponse toResponse(StreamerWalletEntity wallet) {
        return new StreamerWalletResponse(wallet.getId(), wallet.getStreamerId(), wallet.getAvailableBalance());
    }
}
