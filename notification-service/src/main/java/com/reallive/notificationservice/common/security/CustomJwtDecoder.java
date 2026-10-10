package com.reallive.notificationservice.common.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.text.ParseException;

@Component
public class CustomJwtDecoder implements JwtDecoder {

    @Override
    public Jwt decode(String token) throws JwtException {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            if (claims.getIssueTime() == null || claims.getExpirationTime() == null) {
                throw new BadJwtException("Invalid JWT token");
            }
            return new Jwt(
                    token,
                    claims.getIssueTime().toInstant(),
                    claims.getExpirationTime().toInstant(),
                    signedJWT.getHeader().toJSONObject(),
                    claims.toJSONObject());
        } catch (ParseException e) {
            throw new BadJwtException("Invalid JWT token");
        }
    }
}
