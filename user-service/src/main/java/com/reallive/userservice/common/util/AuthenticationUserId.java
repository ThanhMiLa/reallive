package com.reallive.userservice.common.util;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

public final class AuthenticationUserId {

    public AuthenticationUserId() {
    }

    public static UUID getAuthenticatedUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
