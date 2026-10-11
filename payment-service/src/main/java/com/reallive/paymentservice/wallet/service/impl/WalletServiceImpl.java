package com.reallive.paymentservice.wallet.service.impl;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import com.reallive.paymentservice.wallet.dto.response.WalletCreationResponse;
import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import com.reallive.paymentservice.wallet.mapper.WalletMapper;
import com.reallive.paymentservice.wallet.repository.UserWalletRepository;
import com.reallive.paymentservice.wallet.service.WalletService;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.mapstruct.factory.Mappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class WalletServiceImpl implements WalletService {
    UserWalletRepository userWalletRepository;
    WalletMapper walletMapper;

    @Override
    @Transactional
    public WalletCreationResponse createWallet(UUID userId) {
        if (userWalletRepository.existsByUserId(userId)) {
            throw new AppException(ErrorCode.WALLET_ALREADY_EXISTS);
        }

        UserWalletEntity walletEntity = UserWalletEntity.builder()
                .userId(userId)
                .balance(0L)
                .build();
        userWalletRepository.save(walletEntity);

        return walletMapper.toWalletCreationResponse(walletEntity);
    }
}
