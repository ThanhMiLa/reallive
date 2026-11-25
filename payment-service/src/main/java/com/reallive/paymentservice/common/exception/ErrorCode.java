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
    STREAMER_WALLET_NOT_FOUND(1013, "Streamer wallet not found", HttpStatus.NOT_FOUND),
    STREAM_SESSION_NOT_FOUND(1014, "Stream session not found", HttpStatus.NOT_FOUND),
    STREAM_NOT_LIVE(1015, "Stream is not live", HttpStatus.CONFLICT),
    STREAM_NOT_ACCESSIBLE(1016, "Stream is not accessible", HttpStatus.FORBIDDEN),
    STREAM_SERVICE_UNAVAILABLE(1017, "Stream service unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    SELF_DONATION_NOT_ALLOWED(1018, "You cannot donate to your own stream", HttpStatus.BAD_REQUEST),
    VIRTUAL_GIFT_NOT_FOUND(1019, "Virtual gift not found", HttpStatus.NOT_FOUND),
    VIRTUAL_GIFT_INACTIVE(1020, "Virtual gift is no longer available", HttpStatus.CONFLICT),
    INSUFFICIENT_BALANCE(1021, "Insufficient coin balance", HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_CONFLICT(1022, "Idempotency key was already used for a different donation", HttpStatus.CONFLICT);


    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

}
