package com.reallive.userservice.auth.service.impl;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.mapper.UserMapper;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "gateway.test.redis-port", matches = "[0-9]+")
class AuthRefreshConcurrencyTest {

    @Test
    void onlyOneConcurrentRefreshCanConsumeTokenInRedis() throws Exception {
        String signingKey = "refresh-concurrency-key-".repeat(4);
        String jti = UUID.randomUUID().toString();
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of());
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1",
                Integer.parseInt(System.getProperty("gateway.test.redis-port")));
        configuration.setDatabase(4);
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        try (var executor = Executors.newFixedThreadPool(2)) {
            UserRepository users = mock(UserRepository.class);
            when(users.findById(user.getId())).thenReturn(Optional.of(user));
            AuthServiceImpl service = new AuthServiceImpl(users, mock(UserMapper.class),
                    mock(PasswordEncoder.class), mock(RoleRepository.class), redis);
            ReflectionTestUtils.setField(service, "SIGNER_KEY", signingKey);
            ReflectionTestUtils.setField(service, "TTL_ACCESS_TOKEN", 900L);
            ReflectionTestUtils.setField(service, "TTL_REFRESH_TOKEN", 2592000L);
            JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(user.getId().toString()).jwtID(jti)
                    .issuer("reallive.com").issueTime(Date.from(Instant.now().minusSeconds(1)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("type", "REFRESH").build();
            SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
            token.sign(new MACSigner(signingKey.getBytes(StandardCharsets.UTF_8)));
            CyclicBarrier start = new CyclicBarrier(2);
            var first = executor.submit(() -> refresh(service, token.serialize(), start));
            var second = executor.submit(() -> refresh(service, token.serialize(), start));

            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            assertEquals("1", redis.opsForValue().get("auth:revoked:refresh:" + jti));
        } finally {
            try {
                redis.delete("auth:revoked:refresh:" + jti);
            } finally {
                connectionFactory.destroy();
            }
        }
    }

    private int refresh(AuthServiceImpl service, String token, CyclicBarrier start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            service.refresh(token);
            return 1;
        } catch (AppException exception) {
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
            return 0;
        }
    }
}
