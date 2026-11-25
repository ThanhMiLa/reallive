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
    WALLET_NOT_FOUND(1006, "Wallet not found", HttpStatus.NOT_FOUND),
    INVALID_DEPOSIT_PACKAGE(1007, "Invalid deposit package", HttpStatus.BAD_REQUEST),
    PAYMENT_TRANSACTION_NOT_FOUND(1008, "Payment transaction not found", HttpStatus.NOT_FOUND),
    INVALID_REQUEST(1009, "Invalid request", HttpStatus.BAD_REQUEST),
    STREAMER_NOT_FOUND(1010, "Streamer not found", HttpStatus.NOT_FOUND),
    STREAMER_NOT_ACTIVE(1011, "Streamer is not active", HttpStatus.FORBIDDEN),
    USER_SERVICE_UNAVAILABLE(1012, "User service unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    STREAMER_WALLET_NOT_FOUND(1013, "Streamer wallet not found", HttpStatus.NOT_FOUND);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

}
