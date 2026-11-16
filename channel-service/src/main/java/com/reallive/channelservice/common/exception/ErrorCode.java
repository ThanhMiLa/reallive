package com.reallive.channelservice.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    AUTH_STATE_UNAVAILABLE(1018, "Authentication state temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    UNAUTHENTICATED(1001, "Unauthorized", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(1002, "Access Denied", HttpStatus.FORBIDDEN),
    CHANNEL_DATA_UNAVAILABLE(1003, "Channel data unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    CHANNEL_REQUEST_INVALID(1004, "Invalid channel request", HttpStatus.BAD_REQUEST),
    CHANNEL_ALREADY_EXISTED(1005, "Channel already existed", HttpStatus.BAD_REQUEST),
    STREAMER_NOT_FOUND(1006, "Streamer not found", HttpStatus.NOT_FOUND),
    STREAMER_NOT_ACTIVE(1007, "Streamer is not active", HttpStatus.FORBIDDEN),
    USER_SERVICE_UNAVAILABLE(1008, "User service unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    CHANNEL_NOT_FOUND(1009, "Channel not found", HttpStatus.NOT_FOUND),
    CHANNEL_NOT_ACTIVE(1010, "Channel is not active", HttpStatus.FORBIDDEN),
    CATEGORY_NOT_FOUND(1011, "Category not found", HttpStatus.BAD_REQUEST),
    CATEGORY_NOT_ACTIVE(1012, "Category is not active", HttpStatus.BAD_REQUEST),
    BANNER_INVALID(1013, "Banner must be a valid JPEG, PNG or WebP image with at most 16 million pixels", HttpStatus.BAD_REQUEST),
    BANNER_TOO_LARGE(1014, "Banner must not exceed 1 MB", HttpStatus.PAYLOAD_TOO_LARGE),
    BANNER_UPLOAD_FAILED(1015, "Unable to upload banner", HttpStatus.BAD_GATEWAY),
    BANNER_STORAGE_UNAVAILABLE(1016, "Banner storage unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    UNSUPPORTED_MEDIA_TYPE(1017, "Unsupported media type", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    CHANNEL_ALREADY_FOLLOWED(1019, "Channel already followed", HttpStatus.CONFLICT),
    CHANNEL_NOT_FOLLOWED(1020, "Channel is not followed", HttpStatus.CONFLICT),
    CANNOT_FOLLOW_OWN_CHANNEL(1021, "Cannot follow own channel", HttpStatus.CONFLICT),
    USER_NOT_FOUND(1022, "User not found", HttpStatus.NOT_FOUND),
    CHANNEL_CHAT_ALREADY_BANNED(1023, "User already banned from chat", HttpStatus.CONFLICT),
    CHANNEL_CHAT_NOT_BANNED(1024, "User is not banned from chat", HttpStatus.CONFLICT),
    CANNOT_BAN_CHANNEL_OWNER(1025, "Cannot ban channel owner", HttpStatus.CONFLICT),
    CHANNEL_CHAT_BAN_BLOCKED_BY_HIGHER_BAN(1026, "User has a higher channel ban", HttpStatus.CONFLICT),
    CHANNEL_CHAT_UNBAN_BLOCKED_BY_HIGHER_BAN(1027, "Higher channel ban cannot be lifted here", HttpStatus.CONFLICT);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

}
