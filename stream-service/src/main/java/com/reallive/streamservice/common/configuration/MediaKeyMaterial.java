package com.reallive.streamservice.common.configuration;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

public record MediaKeyMaterial(
        RSAPrivateKey privateKey,
        RSAPublicKey publicKey
) {
    @Override
    public String toString() {
        return "MediaKeyMaterial[REDACTED]";
    }
}
