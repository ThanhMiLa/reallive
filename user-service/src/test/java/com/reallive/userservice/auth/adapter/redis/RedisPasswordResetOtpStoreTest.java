package com.reallive.userservice.auth.adapter.redis;

import com.reallive.userservice.user.dto.response.EmailVerificationCooldownResult;
import com.reallive.userservice.user.dto.response.EmailVerificationOtpCheckResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisPasswordResetOtpStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisPasswordResetOtpStore store;
    private UUID userId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        store = new RedisPasswordResetOtpStore(redisTemplate);
        ReflectionTestUtils.setField(store, "ttlSeconds", 60L);
        userId = UUID.randomUUID();
    }

    @Test
    void hasCooldownReturnsTrueWhenKeyExists() {
        when(redisTemplate.hasKey("otp:password-reset:cooldown:" + userId)).thenReturn(true);
        assertTrue(store.hasCooldown(userId));
    }

    @Test
    void hasCooldownReturnsFalseWhenKeyMissing() {
        when(redisTemplate.hasKey("otp:password-reset:cooldown:" + userId)).thenReturn(false);
        assertFalse(store.hasCooldown(userId));
    }

    @Test
    void saveReturnsAcquiredWhenScriptReturnsMinusOne() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("otp:password-reset:cooldown:" + userId)),
                eq("valid-hash"), eq("60"))).thenReturn(-1L);

        EmailVerificationCooldownResult result = store.save(userId, "valid-hash");

        assertTrue(result.acquired());
        assertEquals(Duration.ZERO, result.retryAfter());
    }

    @Test
    void saveReturnsCooldownWhenScriptReturnsRemainingMillis() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("otp:password-reset:cooldown:" + userId)),
                eq("valid-hash"), eq("60"))).thenReturn(45000L);

        EmailVerificationCooldownResult result = store.save(userId, "valid-hash");

        assertFalse(result.acquired());
        assertEquals(Duration.ofMillis(45000), result.retryAfter());
    }

    @Test
    void saveThrowsIllegalStateExceptionWhenScriptReturnsInvalidOutcome() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("otp:password-reset:cooldown:" + userId)),
                eq("valid-hash"), eq("60"))).thenReturn(-2L);

        assertThrows(IllegalStateException.class, () -> store.save(userId, "valid-hash"));
    }

    @Test
    void consumeReturnsNotFoundWhenScriptReturnsZero() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("otp:password-reset:cooldown:" + userId)),
                eq("hash"))).thenReturn(0L);

        assertEquals(EmailVerificationOtpCheckResult.NOT_FOUND, store.consume(userId, "hash"));
    }

    @Test
    void consumeReturnsInvalidWhenScriptReturnsOne() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("otp:password-reset:cooldown:" + userId)),
                eq("hash"))).thenReturn(1L);

        assertEquals(EmailVerificationOtpCheckResult.INVALID, store.consume(userId, "hash"));
    }

    @Test
    void consumeReturnsValidWhenScriptReturnsTwo() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("otp:password-reset:cooldown:" + userId)),
                eq("hash"))).thenReturn(2L);

        assertEquals(EmailVerificationOtpCheckResult.VALID, store.consume(userId, "hash"));
    }

    @Test
    void consumeThrowsIllegalStateExceptionWhenScriptReturnsUnexpectedValue() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("otp:password-reset:cooldown:" + userId)),
                eq("hash"))).thenReturn(99L);

        assertThrows(IllegalStateException.class, () -> store.consume(userId, "hash"));
    }

    @Test
    void saveResetTokenStoresUserIdWithTtl() {
        store.saveResetToken("reset-token-123", userId, Duration.ofSeconds(600));

        verify(valueOperations).set("auth:password-reset:token:reset-token-123", userId.toString(), Duration.ofSeconds(600));
    }

    @Test
    void consumeResetTokenReturnsUserIdAndDeletesAtomically() {
        when(valueOperations.getAndDelete("auth:password-reset:token:reset-token-123")).thenReturn(userId.toString());

        Optional<UUID> consumed = store.consumeResetToken("reset-token-123");

        assertTrue(consumed.isPresent());
        assertEquals(userId, consumed.get());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void consumeResetTokenReturnsEmptyWhenTokenBlank(String token) {
        Optional<UUID> consumed = store.consumeResetToken(token);
        assertTrue(consumed.isEmpty());
    }

    @Test
    void consumeResetTokenReturnsEmptyWhenKeyNotFound() {
        when(valueOperations.getAndDelete("auth:password-reset:token:not-found")).thenReturn(null);

        Optional<UUID> consumed = store.consumeResetToken("not-found");
        assertTrue(consumed.isEmpty());
    }

    @Test
    void consumeResetTokenReturnsEmptyWhenStoredValueIsMalformedUuid() {
        when(valueOperations.getAndDelete("auth:password-reset:token:bad-uuid")).thenReturn("not-a-valid-uuid");

        Optional<UUID> consumed = store.consumeResetToken("bad-uuid");
        assertTrue(consumed.isEmpty());
    }

    @Test
    void deleteIfCurrentDelegatesToConsume() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("otp:password-reset:cooldown:" + userId)),
                eq("hash"))).thenReturn(2L);

        store.deleteIfCurrent(userId, "hash");

        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(List.of("otp:password-reset:cooldown:" + userId)), eq("hash"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void requireHashRejectsNullOrBlankHash(String hash) {
        assertThrows(IllegalArgumentException.class, () -> store.save(userId, hash));
        assertThrows(IllegalArgumentException.class, () -> store.consume(userId, hash));
    }
}
