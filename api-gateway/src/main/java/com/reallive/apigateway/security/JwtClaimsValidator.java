package com.reallive.apigateway.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.UUID;

public class JwtClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private final String issuer;

    public JwtClaimsValidator(String issuer) {
        this.issuer = issuer;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            Instant now = Instant.now();
            Instant issuedAt = token.getIssuedAt();
            Instant expiresAt = token.getExpiresAt();
            Object scope = token.getClaims().get("scope");
            String type = token.getClaimAsString("type");
            UUID.fromString(token.getSubject());
            if (!issuer.equals(token.getClaimAsString("iss"))
                    || token.getId() == null || token.getId().isBlank()
                    || issuedAt == null || issuedAt.isAfter(now)
                    || expiresAt == null || !expiresAt.isAfter(now) || !expiresAt.isAfter(issuedAt)
                    || token.getNotBefore() != null && token.getNotBefore().isAfter(now)
                    || !TokenType.ACCESS.name().equals(type) && !TokenType.REFRESH.name().equals(type)
                    || scope != null && !(scope instanceof String)) {
                return invalidClaims();
            }
            return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException | NullPointerException exception) {
            return invalidClaims();
        }
    }

    private OAuth2TokenValidatorResult invalidClaims() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid JWT claims", null));
    }
}
