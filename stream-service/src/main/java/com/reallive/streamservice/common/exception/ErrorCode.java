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
    AUTH_STATE_UNAVAILABLE(1005, "Authentication state temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    STREAM_SESSION_NOT_FOUND(1006, "Stream session not found", HttpStatus.NOT_FOUND),
    STREAM_SESSION_NOT_OWNED(1007, "Stream session does not belong to current user", HttpStatus.FORBIDDEN),
    STREAM_SESSION_ALREADY_ACTIVE(1008, "Channel already has an active stream session", HttpStatus.CONFLICT),
    STREAM_SESSION_STATE_INVALID(1009, "Stream session state does not allow this operation", HttpStatus.CONFLICT),
    STREAM_CREATION_REQUEST_CONFLICT(1010, "Creation request identifier is already used for another channel", HttpStatus.CONFLICT),
    STREAMER_NOT_ELIGIBLE(1011, "Streamer is not eligible to publish", HttpStatus.FORBIDDEN),
    STREAM_CHANNEL_NOT_ELIGIBLE(1012, "Channel is not eligible to publish", HttpStatus.FORBIDDEN),
    STREAM_CATEGORY_INVALID(1013, "Stream category is invalid or inactive", HttpStatus.BAD_REQUEST);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;
}
