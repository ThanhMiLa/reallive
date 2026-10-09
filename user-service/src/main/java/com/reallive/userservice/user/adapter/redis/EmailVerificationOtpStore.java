package com.reallive.userservice.user.adapter.redis;

import com.reallive.userservice.user.dto.response.EmailVerificationCooldownResult;
import com.reallive.userservice.user.dto.response.EmailVerificationOtpCheckResult;

import java.util.UUID;

public interface EmailVerificationOtpStore {

    /**
     * Checks whether the user's OTP key exists so resend requests can be rejected early.
     *
     * <p>The result reflects only the time of this check. Call {@link #save(UUID, String)}
     * to ensure concurrent requests cannot both store an OTP.</p>
     *
     * @param userId the user whose cooldown is checked
     * @return {@code true} if the key exists; {@code false} otherwise
     */
    boolean hasCooldown(UUID userId);

    /**
     * Stores the OTP hash only if the user's key is absent, with a default TTL of 60 seconds.
     *
     * <p>The TTL determines both OTP validity and the resend cooldown. If the key already
     * exists, preserves its hash and TTL and returns the remaining cooldown.</p>
     *
     * @param userId the user who owns the OTP
     * @param otpHash the HMAC computed from the OTP, user ID and email
     * @return {@code acquired = true} with {@code retryAfter = Duration.ZERO} on success;
     *         otherwise, {@code acquired = false} with the remaining cooldown as {@code retryAfter}
     */
    EmailVerificationCooldownResult save(UUID userId, String otpHash);

    /**
     * Compares the submitted OTP hash and immediately deletes the key if it matches.
     *
     * <p>Comparison and deletion are atomic so an OTP can be consumed successfully only once.
     * A hash mismatch preserves the key and its TTL.</p>
     *
     * @param userId the user verifying their email
     * @param otpHash the HMAC recomputed from the submitted OTP, user ID and database email
     * @return {@link EmailVerificationOtpCheckResult#VALID} if the hash matches and the key is deleted;
     *         {@link EmailVerificationOtpCheckResult#INVALID} if the hash does not match;
     *         {@link EmailVerificationOtpCheckResult#NOT_FOUND} if the key has expired,
     *         has already been consumed or was never created
     */
    EmailVerificationOtpCheckResult consume(UUID userId, String otpHash);

    /**
     * Removes the key when cancelling an OTP request, but only if its current hash still matches.
     *
     * <p>Use this when Kafka publication is known to have failed and the OTP must be withdrawn.
     * Atomic comparison and deletion preserve a different hash stored by a newer request.
     * A missing key or hash mismatch leaves Redis unchanged.</p>
     *
     * @param userId the user who owns the key to remove
     * @param otpHash the HMAC of the OTP request being cancelled
     */
    void deleteIfCurrent(UUID userId, String otpHash);
}
