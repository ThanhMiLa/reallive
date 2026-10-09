package com.reallive.userservice.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    UNAUTHENTICATED(1001, "Unauthorized", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(1002, "Access Denied", HttpStatus.FORBIDDEN),
    USER_NOT_ACTIVE(1003, "User is not active", HttpStatus.FORBIDDEN),
    USER_NOT_FOUND(1004, "User not found", HttpStatus.NOT_FOUND),
    USERNAME_ALREADY_EXISTS(1005, "Username already exists", HttpStatus.CONFLICT),
    EMAIL_ALREADY_EXISTS(1006, "Email already exists", HttpStatus.CONFLICT),
    PASSWORD_NOT_MATCH(1007, "Password does not match", HttpStatus.BAD_REQUEST),
    USERNAME_INVALID(1008, "Username must be at least 5 characters long", HttpStatus.BAD_REQUEST),
    PASSWORD_INVALID(1009, "Password must be at least 5 characters long", HttpStatus.BAD_REQUEST),
    EMAIL_INVALID(1010, "Email must be valid", HttpStatus.BAD_REQUEST),
    PASSWORD_CONFIRM_INVALID(1011, "Confirm password must be at least 5 characters long", HttpStatus.BAD_REQUEST),
    PROFILE_NOT_FOUND(1012, "Profile not found", HttpStatus.NOT_FOUND),
    PROFILE_UPDATE_EMPTY(1013, "At least one profile field is required", HttpStatus.BAD_REQUEST),
    DISPLAY_NAME_INVALID(1014, "Display name must contain 1 to 100 characters", HttpStatus.BAD_REQUEST),
    DOB_INVALID(1015, "Date of birth must be a valid yyyy-MM-dd date and not in the future", HttpStatus.BAD_REQUEST),
    AVATAR_INVALID(1016, "Avatar must be a valid JPEG, PNG or WebP image with at most 16 million pixels", HttpStatus.BAD_REQUEST),
    AVATAR_TOO_LARGE(1017, "Avatar must not exceed 5 MB", HttpStatus.PAYLOAD_TOO_LARGE),
    AVATAR_UPLOAD_FAILED(1018, "Unable to upload avatar", HttpStatus.BAD_GATEWAY),
    OTP_INVALID(1019, "OTP must contain exactly 6 digits", HttpStatus.BAD_REQUEST),
    EMAIL_ALREADY_VERIFIED(1020, "Email is already verified", HttpStatus.CONFLICT),
    OTP_COOLDOWN(1021, "Please wait before requesting another OTP", HttpStatus.TOO_MANY_REQUESTS),
    OTP_REQUEST_UNAVAILABLE(1022, "Unable to request an OTP at this time", HttpStatus.SERVICE_UNAVAILABLE),
    OTP_CONFIGURATION_INVALID(1023, "Email verification configuration is unavailable", HttpStatus.INTERNAL_SERVER_ERROR),
    ;

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

}
