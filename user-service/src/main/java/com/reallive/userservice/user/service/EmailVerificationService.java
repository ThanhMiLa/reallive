package com.reallive.userservice.user.service;

import com.reallive.userservice.user.dto.request.VerifyEmailOtpRequest;
import com.reallive.userservice.user.dto.response.EmailVerificationOtpResponse;
import com.reallive.userservice.user.dto.response.EmailVerificationResponse;

import java.util.UUID;

public interface EmailVerificationService {
    EmailVerificationOtpResponse requestEmailVerificationOtp(UUID userId);

    EmailVerificationResponse verifyEmailOtp(UUID userId, VerifyEmailOtpRequest request);
}
