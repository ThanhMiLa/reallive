package com.reallive.paymentservice.wallet.service;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import com.reallive.paymentservice.wallet.dto.response.StreamerWalletResponse;

import java.util.UUID;

public interface StreamerWalletService {

    /**
     * Ensures an idempotent zero-balance streamer wallet exists for the authenticated user's
     * active streamer account and returns its current state.
     *
     * <p>Resolves and verifies the active streamer identity via User Service outside any database
     * transaction before executing an idempotent insert-or-read inside a short local transaction.
     * Existing balances are preserved when the wallet already exists.</p>
     *
     * @param userId the authenticated user's ID
     * @return the newly created or existing streamer wallet identity, streamer ID, and available balance
     * @throws AppException with {@link ErrorCode#STREAMER_NOT_FOUND} if the user has no streamer record,
     *                      {@link ErrorCode#STREAMER_NOT_ACTIVE} if the streamer is not active,
     *                      {@link ErrorCode#USER_SERVICE_UNAVAILABLE} if User Service cannot be reached or returns an invalid response,
     *                      or {@link ErrorCode#PAYMENT_DATA_UNAVAILABLE} if the wallet cannot be loaded after creation
     */
    StreamerWalletResponse createWallet(UUID userId);

    /**
     * Reads the current available balance of the authenticated user's active streamer wallet.
     *
     * <p>Resolves and verifies the active streamer identity via User Service outside any database
     * transaction before querying the wallet without creating one if absent.</p>
     *
     * @param userId the authenticated user's ID
     * @return the streamer wallet identity, streamer ID, and current available balance
     * @throws AppException with {@link ErrorCode#STREAMER_NOT_FOUND} if the user has no streamer record,
     *                      {@link ErrorCode#STREAMER_NOT_ACTIVE} if the streamer is not active,
     *                      {@link ErrorCode#USER_SERVICE_UNAVAILABLE} if User Service cannot be reached or returns an invalid response,
     *                      or {@link ErrorCode#STREAMER_WALLET_NOT_FOUND} if the streamer has no wallet
     */
    StreamerWalletResponse getBalance(UUID userId);

    /**
     * Ensures a streamer wallet row exists for the given streamer ID within a database transaction
     * and returns its current state.
     *
     * <p>This idempotent operation is safe under concurrent calls and preserves any existing balance
     * for reuse by downstream workflows such as donation settlement.</p>
     *
     * @param streamerId the active streamer's ID
     * @return the newly created or existing streamer wallet identity, streamer ID, and available balance
     * @throws AppException with {@link ErrorCode#PAYMENT_DATA_UNAVAILABLE} if the wallet cannot be loaded after insertion
     */
    StreamerWalletResponse ensureStreamerWallet(UUID streamerId);
}
