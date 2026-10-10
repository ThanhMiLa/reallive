package com.reallive.userservice.auth.adapter.redis;

import com.reallive.userservice.user.dto.response.EmailVerificationCooldownResult;
import com.reallive.userservice.user.dto.response.EmailVerificationOtpCheckResult;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class RedisPasswordResetOtpStore implements PasswordResetOtpStore {

    static String OTP_KEY_PREFIX = "otp:password-reset:cooldown:";
    static String TOKEN_KEY_PREFIX = "auth:password-reset:token:";

    static DefaultRedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2]) then
                return -1
            end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl < 0 then
                return -2
            end
            return ttl
            """, Long.class);

    static DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local stored = redis.call('GET', KEYS[1])
            if not stored then
                return 0
            end
            if stored ~= ARGV[1] then
                return 1
            end
            redis.call('DEL', KEYS[1])
            return 2
            """, Long.class);

    StringRedisTemplate redisTemplate;

    @NonFinal
    @Value("${email-verification.otp.ttl-seconds:60}")
    long ttlSeconds;

    @Override
    public boolean hasCooldown(UUID userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(otpKey(userId)));
    }

    @Override
    public EmailVerificationCooldownResult save(UUID userId, String otpHash) {
        requireHash(otpHash);
        Long remainingMillis = redisTemplate.execute(SAVE_SCRIPT, List.of(otpKey(userId)),
                otpHash, Long.toString(ttlSeconds));
        if (remainingMillis == null || remainingMillis < -1) {
            throw new IllegalStateException("Unable to determine password reset cooldown");
        }
        return remainingMillis == -1
                ? new EmailVerificationCooldownResult(true, Duration.ZERO)
                : new EmailVerificationCooldownResult(false, Duration.ofMillis(remainingMillis));
    }

    @Override
    public EmailVerificationOtpCheckResult consume(UUID userId, String otpHash) {
        requireHash(otpHash);
        Long outcome = redisTemplate.execute(CONSUME_SCRIPT, List.of(otpKey(userId)), otpHash);
        if (outcome == null) {
            throw new IllegalStateException("Unable to consume password reset OTP");
        }
        return switch (outcome.intValue()) {
            case 0 -> EmailVerificationOtpCheckResult.NOT_FOUND;
            case 1 -> EmailVerificationOtpCheckResult.INVALID;
            case 2 -> EmailVerificationOtpCheckResult.VALID;
            default -> throw new IllegalStateException("Unexpected password reset OTP result");
        };
    }

    @Override
    public void saveResetToken(String resetToken, UUID userId, Duration duration) {
        Objects.requireNonNull(resetToken, "Reset token is required");
        Objects.requireNonNull(userId, "User ID is required");
        redisTemplate.opsForValue().set(tokenKey(resetToken), userId.toString(), duration);
    }

    @Override
    public Optional<UUID> consumeResetToken(String resetToken) {
        if (resetToken == null || resetToken.isBlank()) {
            return Optional.empty();
        }
        String storedUserId = redisTemplate.opsForValue().getAndDelete(tokenKey(resetToken));
        if (storedUserId == null || storedUserId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(storedUserId));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void deleteIfCurrent(UUID userId, String otpHash) {
        consume(userId, otpHash);
    }

    private String otpKey(UUID userId) {
        return OTP_KEY_PREFIX + Objects.requireNonNull(userId, "User ID is required");
    }

    private String tokenKey(String resetToken) {
        return TOKEN_KEY_PREFIX + Objects.requireNonNull(resetToken, "Reset token is required");
    }

    private void requireHash(String otpHash) {
        if (otpHash == null || otpHash.isBlank()) {
            throw new IllegalArgumentException("OTP hash is required");
        }
    }
}
