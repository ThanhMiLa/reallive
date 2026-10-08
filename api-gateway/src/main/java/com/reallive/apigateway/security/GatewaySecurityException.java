package com.reallive.apigateway.security;

import org.springframework.http.HttpStatus;

import java.util.Set;

public class GatewaySecurityException extends RuntimeException {

    private final HttpStatus status;
    private final int code;
    private final Set<TokenType> invalidCookies;

    public GatewaySecurityException(HttpStatus status, int code, String message, Set<TokenType> invalidCookies) {
        super(message);
        this.status = status;
        this.code = code;
        this.invalidCookies = Set.copyOf(invalidCookies);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public int getCode() {
        return code;
    }

    public Set<TokenType> getInvalidCookies() {
        return invalidCookies;
    }

    public static GatewaySecurityException unauthorized(Set<TokenType> invalidCookies) {
        return new GatewaySecurityException(HttpStatus.UNAUTHORIZED, 1001, "Unauthorized", invalidCookies);
    }

    public static GatewaySecurityException blacklistUnavailable() {
        return new GatewaySecurityException(HttpStatus.SERVICE_UNAVAILABLE, 1013,
                "Authentication service temporarily unavailable", Set.of());
    }
}
