package com.reallive.paymentservice.deposit.entity;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CoinDepositPackage {
    COIN_100(10_000, 100, "100 Coins"),
    COIN_500(50_000, 500, "500 Coins"),
    COIN_1000(100_000, 1_000, "1,000 Coins"),
    COIN_2000(200_000, 2_000, "2,000 Coins"),
    COIN_5000(500_000, 5_000, "5,000 Coins");

    private final long amountVnd;
    private final long coinAmount;
    private final String displayName;

    public static CoinDepositPackage fromCode(String packageCode) {
        try {
            return valueOf(packageCode);
        } catch (IllegalArgumentException exception) {
            throw new AppException(ErrorCode.INVALID_DEPOSIT_PACKAGE);
        }
    }
}
