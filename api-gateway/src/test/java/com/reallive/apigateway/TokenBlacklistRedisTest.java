package com.reallive.apigateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.apigateway.configuration.GatewaySecurityProperties;
import com.reallive.apigateway.security.JwtTokenVerifier;
import com.reallive.apigateway.security.TokenBlacklistService;
import com.reallive.apigateway.security.TokenType;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfSystemProperty(named = "gateway.test.redis-port", matches = "[0-9]+")
class TokenBlacklistRedisTest {

    private static final String SIGNING_KEY = "redis-integration-key-".repeat(4);

    @ParameterizedTest
    @EnumSource(TokenType.class)
    void gatewayRejectsKeysWrittenWithUserServiceStringSerializer(TokenType type) throws Exception {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1",
                Integer.parseInt(System.getProperty("gateway.test.redis-port")));
        configuration.setDatabase(4);
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate writer = new StringRedisTemplate(connectionFactory);
        String jti = UUID.randomUUID().toString();
        String key = type.blacklistKey(jti);
        try {
            var cookie = new GatewaySecurityProperties.TokenCookie("access_token", "/", false, true, "Lax");
            var refreshCookie = new GatewaySecurityProperties.TokenCookie("refresh_token", "/user/api/auth", false, true, "Lax");
            var properties = new GatewaySecurityProperties(SIGNING_KEY, "reallive.com",
                    Duration.ofSeconds(2), List.of("http://localhost:5173"), cookie, refreshCookie, true);
            var blacklist = new TokenBlacklistService(new ReactiveStringRedisTemplate(connectionFactory), properties);
            var verifier = new JwtTokenVerifier(properties, blacklist);
            String token = signedToken(type, jti);

            assertEquals(jti, verifier.verify(token, type).block(Duration.ofSeconds(5)).getId());
            writer.opsForValue().set(key, "1", Duration.ofSeconds(30));
            assertThrows(JwtException.class, () -> verifier.verify(token, type).block(Duration.ofSeconds(5)));
            TokenType otherType = type == TokenType.ACCESS ? TokenType.REFRESH : TokenType.ACCESS;
            assertFalse(blacklist.isRevoked(otherType, jti).block(Duration.ofSeconds(5)));
        } finally {
            try {
                writer.delete(key);
            } finally {
                connectionFactory.destroy();
            }
        }
    }

    private String signedToken(TokenType type, String jti) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString())
                .issuer("reallive.com").jwtID(jti).issueTime(Date.from(Instant.now().minusSeconds(1)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("type", type.name()).claim("scope", "ROLE_USER").build();
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        token.sign(new MACSigner(SIGNING_KEY.getBytes(StandardCharsets.UTF_8)));
        return token.serialize();
    }
}
