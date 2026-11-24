package com.reallive.paymentservice.wallet.service;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.wallet.dto.response.WalletBalanceResponse;

import java.util.UUID;


public interface WalletService {
    /**
     * Creates a zero-balance wallet when the user has none and returns their current balance.
     *
     * @param userId the authenticated user's ID
     * @return the new or existing wallet identity and balance
     * @throws AppException if the wallet cannot be loaded after creation
     */
    WalletBalanceResponse createWallet(UUID userId);

    /**
     * Reads the current balance of the user's existing wallet.
     *
     * @param userId the authenticated user's ID
     * @return the wallet identity and coin balance
     * @throws AppException if the user has no wallet
     */
    WalletBalanceResponse getBalance(UUID userId);
}
