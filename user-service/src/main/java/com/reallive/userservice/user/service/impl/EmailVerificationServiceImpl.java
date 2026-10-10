package com.reallive.userservice.user.service.impl;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.util.UserValidator;
import com.reallive.userservice.user.adapter.kafka.EmailVerificationEmailPublisher;
import com.reallive.userservice.user.adapter.redis.EmailVerificationOtpStore;
import com.reallive.userservice.user.adapter.kafka.message.EmailType;
import com.reallive.userservice.user.adapter.kafka.message.EmailVerificationRequestedMessage;
import com.reallive.userservice.user.dto.request.VerifyEmailOtpRequest;
import com.reallive.userservice.user.dto.response.EmailVerificationOtpCheckResult;
import com.reallive.userservice.user.dto.response.EmailVerificationOtpResponse;
import com.reallive.userservice.user.dto.response.EmailVerificationResponse;
import com.reallive.userservice.user.exception.EmailVerificationPublishException;
import com.reallive.userservice.user.service.EmailVerificationService;
import com.reallive.userservice.user.util.EmailVerificationOtpHasher;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class EmailVerificationServiceImpl implements EmailVerificationService {
    UserRepository userRepository;
    EmailVerificationOtpStore otpStore;
    EmailVerificationEmailPublisher emailPublisher;
    EmailVerificationOtpHasher otpHasher;
    SecureRandom secureRandom = new SecureRandom();

    @NonFinal
    @Value("${email-verification.otp.ttl-seconds:60}")
    long ttlSeconds;

    @Override
    public EmailVerificationOtpResponse requestEmailVerificationOtp(UUID userId) {

        // Verify user eligibility and check cooldown
        UserEntity user = getEligibleUser(userId);
        checkCooldown(userId);

        // Build OTP and hash it and save it to the store
        String otp = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
        String otpHash = hashOtp(user, otp);
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(ttlSeconds);
        saveOtp(userId, otpHash);

        // Build the email verification message and publish it
        EmailVerificationRequestedMessage message = EmailVerificationRequestedMessage.builder()
                .schemaVersion(1)
                .eventId(UUID.randomUUID())
                .challengeId(UUID.randomUUID())
                .userId(userId)
                .emailType(EmailType.EMAIL_VERIFICATION)
                .recipientEmail(user.getEmail())
                .otp(otp)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        publishEmail(message, otpHash);
        return EmailVerificationOtpResponse.builder().expiresAt(expiresAt).build();
    }

    @Override
    @Transactional
    public EmailVerificationResponse verifyEmailOtp(UUID userId, VerifyEmailOtpRequest request) {
        // Verify user eligibility
        UserEntity user = getEligibleUser(userId);

        // Generate the new hash otp
        String otpHash = hashOtp(user, request.getOtp());

        // Consume the OTP and update the user email verification status
        consumeOtp(userId, otpHash);
        user.setEmailVerified(true);
        userRepository.save(user);
        return EmailVerificationResponse.builder().emailVerified(true).build();
    }

    private void consumeOtp(UUID userId, String otpHash) {
        EmailVerificationOtpCheckResult checkResult;
        try {
            checkResult = otpStore.consume(userId, otpHash);
        } catch (DataAccessException | IllegalStateException exception) {
            throw new AppException(ErrorCode.OTP_VERIFICATION_UNAVAILABLE);
        }
        switch (checkResult) {
            case VALID -> { }
            case INVALID -> throw new AppException(ErrorCode.OTP_MISMATCH);
            case NOT_FOUND -> throw new AppException(ErrorCode.OTP_NOT_FOUND);
        }
    }

    private UserEntity getEligibleUser(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        UserValidator.validateActive(user);
        UserValidator.validateEmailNotVerified(user);
        return user;
    }

    private void checkCooldown(UUID userId) {
        boolean cooldown;
        try {
            cooldown = otpStore.hasCooldown(userId);
        } catch (DataAccessException | IllegalStateException exception) {
            throw new AppException(ErrorCode.OTP_REQUEST_UNAVAILABLE);
        }
        if (cooldown) {
            throw new AppException(ErrorCode.OTP_COOLDOWN);
        }
    }

    private String hashOtp(UserEntity user, String otp) {
        try {
            return otpHasher.hash(user.getId(), user.getEmail(), otp);
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw new AppException(ErrorCode.OTP_CONFIGURATION_INVALID);
        }
    }

    private void saveOtp(UUID userId, String otpHash) {
        boolean acquired;
        try {
            acquired = otpStore.save(userId, otpHash).acquired();
        } catch (DataAccessException | IllegalStateException exception) {
            throw new AppException(ErrorCode.OTP_REQUEST_UNAVAILABLE);
        }
        if (!acquired) {
            throw new AppException(ErrorCode.OTP_COOLDOWN);
        }
    }

    private void publishEmail(EmailVerificationRequestedMessage message, String otpHash) {
        try {
            emailPublisher.publish(message);
        } catch (EmailVerificationPublishException exception) {
            if (!exception.isPublicationUncertain()) {
                cancelOtp(message.getUserId(), otpHash);
            }
            throw new AppException(ErrorCode.OTP_REQUEST_UNAVAILABLE);
        } catch (RuntimeException exception) {
            throw new AppException(ErrorCode.OTP_REQUEST_UNAVAILABLE);
        }
    }

    private void cancelOtp(UUID userId, String otpHash) {
        try {
            otpStore.deleteIfCurrent(userId, otpHash);
        } catch (RuntimeException exception) {
            log.warn("Unable to clean up an email verification request for user {}", userId);
        }
    }
}
