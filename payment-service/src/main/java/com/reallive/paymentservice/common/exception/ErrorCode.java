package com.reallive.paymentservice.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    UNAUTHENTICATED(1001, "Unauthorized", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(1002, "Access Denied", HttpStatus.FORBIDDEN),
    PAYMENT_DATA_UNAVAILABLE(1003, "Payment data is unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    WALLET_ALREADY_EXISTS(1004, "Wallet already exists", HttpStatus.CONFLICT);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

}
