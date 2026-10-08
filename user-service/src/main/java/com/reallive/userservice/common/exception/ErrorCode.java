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
    ;

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

}
