package com.reallive.paymentservice.deposit.dto.response;

public record CoinPackageResponse(
        String packageCode,
        String displayName,
        long amountVnd,
        long coinAmount
) {}
