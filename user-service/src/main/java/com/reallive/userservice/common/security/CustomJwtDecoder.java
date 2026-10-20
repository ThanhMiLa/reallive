package com.reallive.userservice.common.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.text.ParseException;

/**
 * Reads JWT claims after authentication by the trusted API Gateway.
 */
@Component
public class CustomJwtDecoder implements JwtDecoder {

    @Override
    public Jwt decode(String token) throws JwtException {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            return new Jwt(
                    token,
                    claims.getIssueTime().toInstant(),
                    claims.getExpirationTime().toInstant(),
                    signedJWT.getHeader().toJSONObject(),
                    claims.toJSONObject());
        } catch (ParseException exception) {
            throw new BadJwtException("Invalid JWT token");
        }
    }
}
