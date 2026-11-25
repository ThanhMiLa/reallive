package com.reallive.paymentservice.wallet.dto.response;

import java.util.UUID;

public record StreamerWalletResponse(
        UUID walletId,
        UUID streamerId,
        long availableBalance
) {}
