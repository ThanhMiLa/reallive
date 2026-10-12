package com.reallive.channelservice.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    UNAUTHENTICATED(1001, "Unauthorized", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(1002, "Access Denied", HttpStatus.FORBIDDEN),
    CHANNEL_DATA_UNAVAILABLE(1003, "Channel data unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    CHANNEL_REQUEST_INVALID(1004, "Invalid channel request", HttpStatus.BAD_REQUEST),
    CHANNEL_ALREADY_EXISTED(1005, "Channel already existed", HttpStatus.BAD_REQUEST);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

}
