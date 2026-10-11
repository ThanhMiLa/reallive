package com.reallive.paymentservice.wallet.service;

import com.reallive.paymentservice.wallet.dto.response.WalletCreationResponse;

import java.util.UUID;


public interface WalletService {
    WalletCreationResponse createWallet(UUID userId);
}
