package com.reallive.apigateway.security;

import java.util.Locale;

public enum TokenType {
    ACCESS,
    REFRESH;

    public String blacklistKey(String jti) {
        return "auth:revoked:" + name().toLowerCase(Locale.ROOT) + ":" + jti;
    }
}
