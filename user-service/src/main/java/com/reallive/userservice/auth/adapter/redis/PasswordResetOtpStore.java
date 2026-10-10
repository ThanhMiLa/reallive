package com.reallive.userservice.auth.adapter.redis;

import com.reallive.userservice.user.dto.response.EmailVerificationCooldownResult;
import com.reallive.userservice.user.dto.response.EmailVerificationOtpCheckResult;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetOtpStore {

    /**
     * Checks whether the user is currently within an active password reset cooldown period.
     *
     * @param userId the unique identifier of the user requesting an OTP
     * @return true if a cooldown is currently active, false otherwise
     */
    boolean hasCooldown(UUID userId);

    /**
     * Saves a hashed password reset OTP for the user if no cooldown key currently exists.
     *
     * @param userId the unique identifier of the user
     * @param otpHash the cryptographic hash of the generated OTP
     * @return the result indicating whether the key was saved and the remaining duration
     */
    EmailVerificationCooldownResult save(UUID userId, String otpHash);

    /**
     * Consumes the stored password reset OTP atomically if the hash matches.
     *
     * @param userId the unique identifier of the user
     * @param otpHash the cryptographic hash to verify against the stored value
     * @return the check result indicating whether the OTP was valid, invalid, or expired
     */
    EmailVerificationOtpCheckResult consume(UUID userId, String otpHash);

    /**
     * Saves a single-use password reset token associated with a user ID in Redis.
     *
     * @param resetToken the cryptographically secure token value
     * @param userId the unique identifier of the user
     * @param duration the validity lifetime for the reset token
     */
    void saveResetToken(String resetToken, UUID userId, Duration duration);

    /**
     * Atomically retrieves and invalidates a password reset token from Redis.
     *
     * @param resetToken the token value to consume
     * @return an optional containing the associated user ID if valid and unconsumed, empty otherwise
     */
    Optional<UUID> consumeResetToken(String resetToken);

    /**
     * Removes the stored OTP if it matches the specified hash, releasing cooldown.
     *
     * @param userId the unique identifier of the user
     * @param otpHash the hash of the OTP to remove
     */
    void deleteIfCurrent(UUID userId, String otpHash);
}
