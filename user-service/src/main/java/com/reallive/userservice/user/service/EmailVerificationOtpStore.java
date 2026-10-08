package com.reallive.userservice.user.service;

import com.reallive.userservice.user.model.EmailVerificationChallenge;
import com.reallive.userservice.user.model.EmailVerificationCooldownResult;
import com.reallive.userservice.user.model.EmailVerificationOtpCheckResult;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationOtpStore {

    /** Atomically reserves cooldown before OTP generation; rejected requests do not extend its TTL. */
    EmailVerificationCooldownResult acquireCooldown(UUID userId, UUID challengeId, Duration cooldown);

    /** Replaces the challenge only while its challengeId still owns the active cooldown. */
    boolean saveChallenge(EmailVerificationChallenge challenge, int maxAttempts);

    Optional<EmailVerificationChallenge> findChallenge(UUID userId);

    /** Checks the current challenge, expiration and attempts, consuming a valid OTP exactly once. */
    EmailVerificationOtpCheckResult consume(
            UUID userId, UUID challengeId, String email, String otpHash);

    /** Removes challenge and cooldown only if they still belong to this challenge. */
    void deleteIfCurrent(UUID userId, UUID challengeId);
}
