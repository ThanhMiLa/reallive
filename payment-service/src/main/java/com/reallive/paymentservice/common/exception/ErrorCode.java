package com.reallive.paymentservice.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    AUTH_STATE_UNAVAILABLE(1005, "Authentication state temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    UNAUTHENTICATED(1001, "Unauthorized", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(1002, "Access Denied", HttpStatus.FORBIDDEN),
    PAYMENT_DATA_UNAVAILABLE(1003, "Payment data is unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    WALLET_NOT_FOUND(1006, "Wallet not found", HttpStatus.NOT_FOUND);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

}
