package com.reallive.userservice.user.service.impl;

import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.user.dto.request.VerifyEmailOtpRequest;
import com.reallive.userservice.user.dto.response.EmailVerificationOtpResponse;
import com.reallive.userservice.user.dto.response.EmailVerificationResponse;
import com.reallive.userservice.user.service.EmailVerificationEmailPublisher;
import com.reallive.userservice.user.service.EmailVerificationOtpStore;
import com.reallive.userservice.user.service.EmailVerificationService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.UUID;

/**
 * Service scaffold; registered as a Spring bean once verification and its adapters are implemented.
 */
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class EmailVerificationServiceImpl implements EmailVerificationService {
    UserRepository userRepository;
    EmailVerificationOtpStore otpStore;
    EmailVerificationEmailPublisher emailPublisher;

    @Override
    public EmailVerificationOtpResponse requestEmailVerificationOtp(UUID userId) {
        throw new UnsupportedOperationException("Email verification OTP requests are not implemented yet");
    }

    @Override
    public EmailVerificationResponse verifyEmailOtp(UUID userId, VerifyEmailOtpRequest request) {
        throw new UnsupportedOperationException("Email verification is not implemented yet");
    }
}
