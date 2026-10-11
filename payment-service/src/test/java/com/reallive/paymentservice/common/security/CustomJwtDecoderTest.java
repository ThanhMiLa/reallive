package com.reallive.paymentservice.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomJwtDecoderTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-10-11T00:00:00Z");
    private final CustomJwtDecoder decoder = new CustomJwtDecoder();

    @Test
    void readsIdentityTimesHeaderAndAuthoritiesFromGatewayToken() throws Exception {
        String token = token(new JWTClaimsSet.Builder().subject("user-id")
                .issueTime(Date.from(ISSUED_AT)).expirationTime(Date.from(ISSUED_AT.plusSeconds(900)))
                .claim("scope", "USER ADMIN").claim("type", "ACCESS").jwtID("token-id").build());

        var jwt = decoder.decode(token);

        assertEquals(token, jwt.getTokenValue());
        assertEquals("user-id", jwt.getSubject());
        assertEquals("token-id", jwt.getId());
        assertEquals(ISSUED_AT, jwt.getIssuedAt());
        assertEquals(ISSUED_AT.plusSeconds(900), jwt.getExpiresAt());
        assertEquals("HS512", jwt.getHeaders().get("alg"));
        assertEquals("USER ADMIN", jwt.getClaimAsString("scope"));
        assertEquals("ACCESS", jwt.getClaimAsString("type"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "not-a-token", "{}", "abc.def.ghi"})
    void malformedTokensRaiseJwtException(String token) {
        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"iat", "exp"})
    void missingRequiredTimestampRaisesJwtException(String missingClaim) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("user-id");
        if (!"iat".equals(missingClaim)) {
            claims.issueTime(Date.from(ISSUED_AT));
        }
        if (!"exp".equals(missingClaim)) {
            claims.expirationTime(Date.from(ISSUED_AT.plusSeconds(900)));
        }

        assertThrows(JwtException.class, () -> decoder.decode(token(claims.build())));
    }

    @Test
    void expirationBeforeIssueTimeRaisesJwtException() throws Exception {
        String token = token(new JWTClaimsSet.Builder().subject("user-id")
                .issueTime(Date.from(ISSUED_AT)).expirationTime(Date.from(ISSUED_AT.minusSeconds(1))).build());

        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    private String token(JWTClaimsSet claims) throws Exception {
        var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signed.sign(new MACSigner("payment-common-test-secret-".repeat(4)));
        return signed.serialize();
    }
}
