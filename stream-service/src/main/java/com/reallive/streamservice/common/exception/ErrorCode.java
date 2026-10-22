package com.reallive.streamservice.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    UNAUTHENTICATED(1001, "Unauthorized", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(1002, "Access Denied", HttpStatus.FORBIDDEN),
    STREAM_DATA_UNAVAILABLE(1003, "Stream data unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    STREAM_REQUEST_INVALID(1004, "Invalid stream request", HttpStatus.BAD_REQUEST),
    AUTH_STATE_UNAVAILABLE(1005, "Authentication state temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;
}
