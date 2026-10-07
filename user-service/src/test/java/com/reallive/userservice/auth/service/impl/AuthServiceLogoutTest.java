package com.reallive.userservice.auth.service.impl;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.mapper.UserMapper;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AuthServiceLogoutTest {

    private static final String SIGNING_KEY = "test-only-signing-key-".repeat(4);

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private AuthServiceImpl authService;
    private UserRepository userRepository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(String.class), eq("1"), any(Duration.class))).thenReturn(true);
        userRepository = mock(UserRepository.class);
        authService = new AuthServiceImpl(userRepository, mock(UserMapper.class),
                mock(PasswordEncoder.class), mock(RoleRepository.class), redisTemplate);
    }

    @Test
    void logoutRevokesBothTokensUntilTheirOwnExpiration() throws JOSEException {
        Instant now = Instant.now();
        String accessJti = UUID.randomUUID().toString();
        String refreshJti = UUID.randomUUID().toString();
        String accessToken = token("ACCESS", accessJti, now.plusSeconds(300), SIGNING_KEY);
        String refreshToken = token("REFRESH", refreshJti, now.plusSeconds(3600), SIGNING_KEY);

        authService.logout(accessToken, refreshToken);

        ArgumentCaptor<Duration> accessTtl = ArgumentCaptor.forClass(Duration.class);
        ArgumentCaptor<Duration> refreshTtl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).setIfAbsent(eq("auth:revoked:access:" + accessJti), eq("1"), accessTtl.capture());
        verify(valueOperations).setIfAbsent(eq("auth:revoked:refresh:" + refreshJti), eq("1"), refreshTtl.capture());
        assertTrue(accessTtl.getValue().isPositive());
        assertTrue(accessTtl.getValue().compareTo(Duration.ofSeconds(300)) <= 0);
        assertTrue(refreshTtl.getValue().compareTo(accessTtl.getValue()) > 0);
        assertTrue(refreshTtl.getValue().compareTo(Duration.ofSeconds(3600)) <= 0);
        verifyNoMoreInteractions(valueOperations);
    }

    @Test
    void expiredAccessTokenDoesNotPreventRefreshTokenRevocation() throws JOSEException {
        String refreshJti = UUID.randomUUID().toString();
        String expiredAccess = token("ACCESS", UUID.randomUUID().toString(),
                Instant.now().minusSeconds(60), SIGNING_KEY);
        String refreshToken = token("REFRESH", refreshJti, Instant.now().plusSeconds(3600), SIGNING_KEY);

        authService.logout(expiredAccess, refreshToken);

        verify(valueOperations).setIfAbsent(eq("auth:revoked:refresh:" + refreshJti), eq("1"), any(Duration.class));
        verifyNoMoreInteractions(valueOperations);
    }

    @Test
    void missingTokensRequireNoRedisWrites() {
        authService.logout(null, " ");

        verifyNoInteractions(redisTemplate, valueOperations);
    }

    @Test
    void malformedTokensCannotPopulateBlacklist() {
        AppException exception = assertThrows(AppException.class,
                () -> authService.logout("not-a-jwt", null));

        assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        verifyNoInteractions(redisTemplate, valueOperations);
    }

    @Test
    void redisWriteFailureIsPropagatedInsteadOfReportingSuccessfulLogout() throws JOSEException {
        RedisConnectionFailureException failure = new RedisConnectionFailureException("Redis unavailable");
        doThrow(failure).when(valueOperations).setIfAbsent(any(String.class), eq("1"), any(Duration.class));
        String accessToken = token("ACCESS", UUID.randomUUID().toString(),
                Instant.now().plusSeconds(300), SIGNING_KEY);

        RedisConnectionFailureException exception = assertThrows(RedisConnectionFailureException.class,
                () -> authService.logout(accessToken, null));

        assertSame(failure, exception);
    }

    @Test
    void logoutRemainsSuccessfulWhenTokenWasAlreadyRevoked() throws JOSEException {
        String jti = UUID.randomUUID().toString();
        String accessToken = token("ACCESS", jti, Instant.now().plusSeconds(300), SIGNING_KEY);
        when(valueOperations.setIfAbsent(any(String.class), eq("1"), any(Duration.class)))
                .thenReturn(true, false);

        authService.logout(accessToken, null);
        assertDoesNotThrow(() -> authService.logout(accessToken, null));

        verify(valueOperations, times(2)).setIfAbsent(eq("auth:revoked:access:" + jti), eq("1"), any(Duration.class));
        verifyNoMoreInteractions(valueOperations);
    }

    @Test
    void refreshCannotUseTokenRevokedByLogout() throws JOSEException {
        UserEntity user = prepareRefreshUser();
        String refreshToken = refreshToken(user, Instant.now().plusSeconds(300));
        ConcurrentHashMap<String, String> blacklist = new ConcurrentHashMap<>();
        when(valueOperations.setIfAbsent(any(String.class), eq("1"), any(Duration.class)))
                .thenAnswer(invocation -> blacklist.putIfAbsent(invocation.getArgument(0), "1") == null);

        authService.logout(null, refreshToken);
        AppException exception = assertThrows(AppException.class, () -> authService.refresh(refreshToken));

        assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        assertEquals(1, blacklist.size());
    }

    @Test
    void concurrentRefreshRequestsCanConsumeTokenOnlyOnce() throws Exception {
        UserEntity user = prepareRefreshUser();
        String refreshToken = refreshToken(user, Instant.now().plusSeconds(300));
        ConcurrentHashMap<String, String> blacklist = new ConcurrentHashMap<>();
        CyclicBarrier simultaneousConsumption = new CyclicBarrier(2);
        when(valueOperations.setIfAbsent(any(String.class), eq("1"), any(Duration.class)))
                .thenAnswer(invocation -> {
                    simultaneousConsumption.await(5, TimeUnit.SECONDS);
                    return blacklist.putIfAbsent(invocation.getArgument(0), "1") == null;
                });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> refreshSucceeded(refreshToken));
            var second = executor.submit(() -> refreshSucceeded(refreshToken));

            int successes = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        }
    }

    @Test
    void expiredRefreshTokenCannotIssueNewTokens() throws JOSEException {
        UserEntity user = prepareRefreshUser();
        String refreshToken = refreshToken(user, Instant.now().minusSeconds(60));

        AppException exception = assertThrows(AppException.class, () -> authService.refresh(refreshToken));

        assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
        verifyNoInteractions(redisTemplate, valueOperations);
    }

    private UserEntity prepareRefreshUser() {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        ReflectionTestUtils.setField(authService, "SIGNER_KEY", SIGNING_KEY);
        ReflectionTestUtils.setField(authService, "TTL_ACCESS_TOKEN", 900L);
        ReflectionTestUtils.setField(authService, "TTL_REFRESH_TOKEN", 3600L);
        return user;
    }

    private boolean refreshSucceeded(String token) {
        try {
            authService.refresh(token);
            return true;
        } catch (AppException exception) {
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
            return false;
        }
    }

    private String refreshToken(UserEntity user, Instant expirationTime) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .jwtID(UUID.randomUUID().toString())
                .expirationTime(Date.from(expirationTime))
                .claim("type", "REFRESH")
                .build();
        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signedJwt.sign(new MACSigner(SIGNING_KEY.getBytes(StandardCharsets.UTF_8)));
        return signedJwt.serialize();
    }

    private String token(String type, String jti, Instant expirationTime, String signingKey) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID(jti)
                .expirationTime(expirationTime == null ? null : Date.from(expirationTime))
                .claim("type", type)
                .build();
        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signedJwt.sign(new MACSigner(signingKey.getBytes(StandardCharsets.UTF_8)));
        return signedJwt.serialize();
    }
}
