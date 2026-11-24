package com.reallive.paymentservice.wallet.dto.response;

import java.util.UUID;

public record WalletBalanceResponse(
        UUID walletId,
        UUID userId,
        long balance
) {}
