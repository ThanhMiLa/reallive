package com.reallive.notificationservice.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomJwtDecoderTest {

    private final CustomJwtDecoder decoder = new CustomJwtDecoder();
    private static final Instant ISSUED_AT = Instant.parse("2026-10-10T00:00:00Z");

    @Test
    void readsIdentityTimesHeaderAndClaimsFromTheDownstreamToken() throws Exception {
        String token = token(new JWTClaimsSet.Builder().subject("user-id").issueTime(Date.from(ISSUED_AT))
                .expirationTime(Date.from(ISSUED_AT.plusSeconds(900))).claim("scope", "USER ADMIN")
                .claim("email", "user@example.com").build());
        var jwt = decoder.decode(token);
        assertEquals(token, jwt.getTokenValue());
        assertEquals("user-id", jwt.getSubject());
        assertEquals(ISSUED_AT, jwt.getIssuedAt());
        assertEquals(ISSUED_AT.plusSeconds(900), jwt.getExpiresAt());
        assertEquals("HS512", jwt.getHeaders().get("alg"));
        assertEquals("USER ADMIN", jwt.getClaimAsString("scope"));
        assertEquals("user@example.com", jwt.getClaimAsString("email"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-a-token", "{}", "abc.def.ghi"})
    void malformedTokensRaiseJwtException(String token) {
        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @Test
    void missingIssueTimeRaisesJwtException() throws Exception {
        String token = token(new JWTClaimsSet.Builder().subject("user-id")
                .expirationTime(Date.from(ISSUED_AT.plusSeconds(900))).build());
        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @Test
    void missingExpirationTimeRaisesJwtException() throws Exception {
        String token = token(new JWTClaimsSet.Builder().subject("user-id")
                .issueTime(Date.from(ISSUED_AT)).build());
        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    private String token(JWTClaimsSet claims) throws Exception {
        var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signed.sign(new MACSigner("common-jwt-test-secret-".repeat(4)));
        return signed.serialize();
    }
}
