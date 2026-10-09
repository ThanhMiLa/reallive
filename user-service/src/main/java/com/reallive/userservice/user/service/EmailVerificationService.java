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
     * @param userId the user ID extracted from the authenticated principal
     * @param request the six-digit OTP submitted by the user
     * @return the resulting email verification status once implemented
     * @throws UnsupportedOperationException while verification remains scheduled for step 8
     */
    EmailVerificationResponse verifyEmailOtp(UUID userId, VerifyEmailOtpRequest request);
}
