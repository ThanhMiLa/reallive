package com.reallive.notificationservice.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    AUTH_STATE_UNAVAILABLE(1013, "Authentication state temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    UNAUTHENTICATED(1001, "Unauthorized", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(1002, "Access Denied", HttpStatus.FORBIDDEN),
    EMAIL_MESSAGE_INVALID(1003, "Invalid email request message", HttpStatus.BAD_REQUEST),
    EMAIL_MESSAGE_CONFLICT(1004, "Email event identity conflicts with its history", HttpStatus.CONFLICT),
    EMAIL_DELIVERY_REJECTED(1005, "Email submission was rejected", HttpStatus.BAD_GATEWAY),
    EMAIL_DELIVERY_RETRYABLE(1006, "Email submission may be retried", HttpStatus.SERVICE_UNAVAILABLE),
    EMAIL_DELIVERY_UNKNOWN(1007, "Email submission outcome is unknown", HttpStatus.BAD_GATEWAY),
    EMAIL_DELIVERY_RETRY_EXHAUSTED(1008, "Email submission retry limit reached", HttpStatus.BAD_GATEWAY),
    EMAIL_HISTORY_UNAVAILABLE(1009, "Email submission history is unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    EMAIL_WEBHOOK_UNAUTHENTICATED(1010, "Invalid or missing email webhook signature", HttpStatus.UNAUTHORIZED),
    EMAIL_WEBHOOK_INVALID(1011, "Invalid email webhook events", HttpStatus.BAD_REQUEST),
    EMAIL_WEBHOOK_CONFIGURATION_INVALID(1012, "Email webhook verification is unavailable", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

}
