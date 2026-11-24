package com.reallive.paymentservice.wallet.service.impl;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import com.reallive.paymentservice.wallet.dto.response.WalletBalanceResponse;
import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import com.reallive.paymentservice.wallet.repository.UserWalletRepository;
import com.reallive.paymentservice.wallet.service.WalletService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class WalletServiceImpl implements WalletService {
    UserWalletRepository userWalletRepository;

    @Override
    @Transactional
    public WalletBalanceResponse createWallet(UUID userId) {
        userWalletRepository.insertIfAbsent(userId);
        return readBalance(userId, ErrorCode.PAYMENT_DATA_UNAVAILABLE);
    }

    @Override
    @Transactional(readOnly = true)
    public WalletBalanceResponse getBalance(UUID userId) {
        return readBalance(userId, ErrorCode.WALLET_NOT_FOUND);
    }

    private WalletBalanceResponse readBalance(UUID userId, ErrorCode missingWallet) {
        UserWalletEntity wallet = userWalletRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(missingWallet));
        return new WalletBalanceResponse(wallet.getId(), wallet.getUserId(), wallet.getBalance());
    }
}
