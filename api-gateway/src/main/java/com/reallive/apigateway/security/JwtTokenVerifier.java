package com.reallive.apigateway.security;

import com.reallive.apigateway.configuration.GatewaySecurityProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import reactor.core.publisher.Mono;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Component
public class JwtTokenVerifier {

    private final NimbusReactiveJwtDecoder decoder;
    private final TokenBlacklistService blacklistService;

    public JwtTokenVerifier(GatewaySecurityProperties properties, TokenBlacklistService blacklistService) {
        Assert.hasText(properties.signerKey(), "JWT_SIGNER_KEY is required");
        byte[] signingKey = properties.signerKey().getBytes(StandardCharsets.UTF_8);
        Assert.isTrue(signingKey.length >= 64, "HS512 signing key must contain at least 64 UTF-8 bytes");
        decoder = NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(signingKey, "HmacSHA512"))
                .macAlgorithm(MacAlgorithm.HS512)
                .build();
        decoder.setJwtValidator(new JwtClaimsValidator(properties.issuer()));
        this.blacklistService = blacklistService;
    }

    public Mono<Jwt> verify(String token, TokenType expectedType) {
        return Mono.defer(() -> decoder.decode(token))
                .flatMap(jwt -> {
                    if (!expectedType.name().equals(jwt.getClaimAsString("type"))) {
                        return Mono.error(new BadJwtException("Unexpected token type"));
                    }
                    return blacklistService.isRevoked(expectedType, jwt.getId())
                            .flatMap(revoked -> revoked
                                    ? Mono.error(new BadJwtException("Token revoked")) : Mono.just(jwt));
                });
    }
}
