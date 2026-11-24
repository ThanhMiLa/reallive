package com.reallive.paymentservice.deposit.service;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.deposit.dto.response.CoinPackageResponse;
import com.reallive.paymentservice.deposit.dto.response.CreateVnpayDepositResponse;
import com.reallive.paymentservice.deposit.dto.response.DepositStatusResponse;

import java.util.List;
import java.util.UUID;

public interface DepositService {

    /**
     * Lists the fixed packages available for coin deposits.
     *
     * @return packages ordered by coin amount
     */
    List<CoinPackageResponse> getPackages();

    /**
     * Creates a pending deposit and signs the URL the user can use to pay before expiration.
     *
     * @param userId the authenticated user making the deposit
     * @param packageCode the selected fixed package code
     * @param ipAddress the IPv4 address to include in the checkout request
     * @return the new pending order and its checkout URL
     * @throws AppException if the package is unknown, the wallet is absent, or signing is unavailable
     */
    CreateVnpayDepositResponse createVnpayDeposit(UUID userId, String packageCode, String ipAddress);

    /**
     * Reads a user's deposit and the current balance of its wallet.
     *
     * @param userId the authenticated user requesting the order
     * @param orderCode the deposit order reference
     * @return the order's current status and wallet balance
     * @throws AppException if the order is absent or belongs to another user
     */
    DepositStatusResponse getStatus(UUID userId, long orderCode);
}
