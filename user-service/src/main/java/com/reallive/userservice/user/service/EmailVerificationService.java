package com.reallive.userservice.user.service;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.user.dto.request.VerifyEmailOtpRequest;
import com.reallive.userservice.user.dto.response.EmailVerificationOtpResponse;
import com.reallive.userservice.user.dto.response.EmailVerificationResponse;

import java.util.UUID;

public interface EmailVerificationService {
    /**
     * Requests an OTP for the authenticated user's current, unverified database email.
     *
     * <p>Stores a hash with the shared OTP/cooldown TTL and returns only after Kafka acknowledges
     * the message. This operation does not change email verification or roles.</p>
     *
     * @param userId the user ID extracted from the authenticated principal
     * @return the OTP expiration time after the request has been accepted by Kafka
     * @throws AppException if the user is ineligible,
     *         cooldown is active, or configuration/infrastructure prevents the request
     */
    EmailVerificationOtpResponse requestEmailVerificationOtp(UUID userId);

    /**
     * Verifies an OTP for the authenticated user's database email.
     *
     * <p>Consumes the matching OTP once and persists email verification in a database transaction.
     * A wrong or unavailable OTP does not modify the user. This method does not check account
     * status or bypass OTP consumption for an already verified email. If persistence fails after
     * consumption, the database rolls back and the user must request a new OTP.</p>
     *
     * @param userId the user ID extracted from the authenticated principal
     * @param request the six-digit OTP submitted by the user
     * @return an email verification status of {@code true} after successful verification
     * @throws AppException if the user is missing, the OTP is wrong or unavailable,
     *         or configuration/infrastructure prevents verification
     */
    EmailVerificationResponse verifyEmailOtp(UUID userId, VerifyEmailOtpRequest request);
}
