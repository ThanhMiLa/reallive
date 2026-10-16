package com.reallive.userservice.auth.service.impl;

import com.reallive.userservice.auth.adapter.kafka.PasswordResetEmailPublisher;
import com.reallive.userservice.auth.adapter.redis.PasswordResetOtpStore;
import com.reallive.userservice.auth.adapter.redis.TokenRevocation;
import com.reallive.userservice.auth.dto.request.AuthRequest;
import com.reallive.userservice.auth.dto.request.ChangePasswordRequest;
import com.reallive.userservice.auth.dto.request.ForgotPasswordRequest;
import com.reallive.userservice.auth.dto.request.RegisterRequest;
import com.reallive.userservice.auth.dto.request.ResetPasswordRequest;
import com.reallive.userservice.auth.dto.request.VerifyResetOtpRequest;
import com.reallive.userservice.auth.dto.response.AuthResponse;
import com.reallive.userservice.auth.dto.response.ForgotPasswordResponse;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.mapper.UserMapper;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.auth.service.AuthService;
import com.reallive.userservice.auth.service.AuthTokenService;
import com.reallive.userservice.auth.util.PasswordResetOtpHasher;
import com.reallive.userservice.client.wallet.WalletClient;
import com.reallive.userservice.client.wallet.WalletCreationResponse;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.adapter.kafka.message.EmailType;
import com.reallive.userservice.user.adapter.kafka.message.EmailVerificationRequestedMessage;
import com.reallive.userservice.user.dto.response.EmailVerificationOtpCheckResult;
import com.reallive.userservice.user.entity.ProfileEntity;
import com.reallive.userservice.user.repository.ProfileRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class AuthServiceImpl implements AuthService {

    UserRepository userRepository;
    UserMapper userMapper;
    PasswordEncoder passwordEncoder;
    RoleRepository roleRepository;
    AuthTokenService authTokenService;
    ProfileRepository profileRepository;
    PasswordResetOtpStore passwordResetOtpStore;
    PasswordResetEmailPublisher passwordResetEmailPublisher;
    PasswordResetOtpHasher passwordResetOtpHasher;
    WalletClient walletClient;
    SecureRandom secureRandom = new SecureRandom();

    @NonFinal
    @Value("${email-verification.otp.ttl-seconds:60}")
    long otpTtlSeconds;

    @NonFinal
    @Value("${auth.cookie.reset-token.max-age:600}")
    long resetTokenTtlSeconds;

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(AuthRequest authRequest) {
        UserEntity userEntity = userRepository.findByUsername(authRequest.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        boolean isCorrectPassword = passwordEncoder.matches(authRequest.getPassword(), userEntity.getPasswordHash());

        if (!isCorrectPassword) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        return authTokenService.issue(userEntity, List.of());
    }

    @Override
    public void logout(String accessToken, String refreshToken) {
        authTokenService.logout(accessToken, refreshToken);
    }

    @Override
    @Transactional
    public AuthResponse refresh(String token) {
        UUID userId = authTokenService.refreshUserId(token);
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        return authTokenService.issue(user, authTokenService.revocation(token, "REFRESH"));
    }

    @Override
    @Transactional
    public AuthResponse changePassword(UUID userId, String accessToken, String refreshToken, ChangePasswordRequest request) {
        UserEntity userEntity = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        if (userEntity.getPasswordHash() == null || userEntity.getPasswordHash().isBlank()) {
            throw new AppException(ErrorCode.PASSWORD_NOT_SET);
        }

        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        if (!Objects.equals(request.getNewPassword(), request.getConfirmPassword())) {
            throw new AppException(ErrorCode.PASSWORD_NOT_MATCH);
        }

        if (!passwordEncoder.matches(request.getCurrentPassword(), userEntity.getPasswordHash())) {
            throw new AppException(ErrorCode.CURRENT_PASSWORD_INCORRECT);
        }

        if (passwordEncoder.matches(request.getNewPassword(), userEntity.getPasswordHash())) {
            throw new AppException(ErrorCode.NEW_PASSWORD_SAME_AS_CURRENT);
        }

        userEntity.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(userEntity);

        List<TokenRevocation> revocations = new ArrayList<>(authTokenService.revocation(accessToken, "ACCESS"));
        revocations.addAll(authTokenService.revocation(refreshToken, "REFRESH"));
        return authTokenService.issue(userEntity, revocations);
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest registerRequest) {
        if (userRepository.existsByUsername(registerRequest.getUsername())) {
            throw new AppException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }

        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        if (!Objects.equals(registerRequest.getPassword(), registerRequest.getConfirmPassword())) {
            throw new AppException(ErrorCode.PASSWORD_NOT_MATCH);
        }

        // Build User
        UserEntity userEntity = userMapper.toUserEntity(registerRequest);
        userEntity.setPasswordHash(passwordEncoder.encode(registerRequest.getPassword()));
        userEntity.setRoles(new HashSet<>(Set.of(roleRepository.findByName("USER")
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND)))));
        userEntity = userRepository.save(userEntity);

        //Build Profile
        ProfileEntity profile = buildProfileEntity(userEntity);
        profileRepository.save(profile);

        //Build Wallet
        createWallet(userEntity.getId());

        AuthRequest authenticationRequest = AuthRequest.builder()
                .username(registerRequest.getUsername())
                .password(registerRequest.getPassword())
                .build();

        return login(authenticationRequest);

    }

    private ProfileEntity buildProfileEntity(UserEntity userEntity) {
        return ProfileEntity.builder()
                .user(userEntity)
                .displayName(userEntity.getUsername())
                .build();
    }

    @Override
    public ForgotPasswordResponse forgotPassword(ForgotPasswordRequest request) {
        UserEntity user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        checkResetCooldown(user.getId());

        String otp = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
        String otpHash = hashResetOtp(user, otp);
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(otpTtlSeconds);
        saveResetOtp(user.getId(), otpHash);

        EmailVerificationRequestedMessage message = EmailVerificationRequestedMessage.builder()
                .schemaVersion(1)
                .eventId(UUID.randomUUID())
                .challengeId(UUID.randomUUID())
                .userId(user.getId())
                .emailType(EmailType.PASSWORD_RESET)
                .recipientEmail(user.getEmail())
                .otp(otp)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        publishResetEmail(message, otpHash);
        return ForgotPasswordResponse.builder().expiresAt(expiresAt).build();
    }

    @Override
    public String verifyResetOtp(VerifyResetOtpRequest request) {
        UserEntity user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        String otpHash = hashResetOtp(user, request.getOtp());
        consumeResetOtp(user.getId(), otpHash);

        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String resetToken = HexFormat.of().formatHex(tokenBytes);

        try {
            passwordResetOtpStore.saveResetToken(resetToken, user.getId(), Duration.ofSeconds(resetTokenTtlSeconds));
        } catch (DataAccessException | IllegalStateException exception) {
            throw new AppException(ErrorCode.OTP_VERIFICATION_UNAVAILABLE);
        }

        return resetToken;
    }

    @Override
    @Transactional
    public void resetPassword(String resetToken, ResetPasswordRequest request) {
        if (resetToken == null || resetToken.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        if (!Objects.equals(request.getNewPassword(), request.getConfirmPassword())) {
            throw new AppException(ErrorCode.PASSWORD_NOT_MATCH);
        }

        UUID userId;
        try {
            userId = passwordResetOtpStore.consumeResetToken(resetToken)
                    .orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        } catch (DataAccessException | IllegalStateException exception) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        if (user.getPasswordHash() != null && passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new AppException(ErrorCode.NEW_PASSWORD_SAME_AS_CURRENT);
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    private WalletCreationResponse createWallet(UUID userId) {
        try {
            var walletResponse = walletClient.createWallet(userId).getBody().getResult();
            return WalletCreationResponse.builder()
                    .id(walletResponse.getId())
                    .userId(walletResponse.getUserId())
                    .balance(walletResponse.getBalance())
                    .build();
        } catch (AppException exception) {
            throw new AppException(ErrorCode.PAYMENT_SERVICE_UNAVAILABLE);
        }
    }

    private void checkResetCooldown(UUID userId) {
        boolean cooldown;
        try {
            cooldown = passwordResetOtpStore.hasCooldown(userId);
        } catch (DataAccessException | IllegalStateException exception) {
            throw new AppException(ErrorCode.OTP_REQUEST_UNAVAILABLE);
        }
        if (cooldown) {
            throw new AppException(ErrorCode.OTP_COOLDOWN);
        }
    }

    private String hashResetOtp(UserEntity user, String otp) {
        try {
            return passwordResetOtpHasher.hash(user.getId(), user.getEmail(), otp);
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw new AppException(ErrorCode.OTP_CONFIGURATION_INVALID);
        }
    }

    private void saveResetOtp(UUID userId, String otpHash) {
        boolean acquired;
        try {
            acquired = passwordResetOtpStore.save(userId, otpHash).acquired();
        } catch (DataAccessException | IllegalStateException exception) {
            throw new AppException(ErrorCode.OTP_REQUEST_UNAVAILABLE);
        }
        if (!acquired) {
            throw new AppException(ErrorCode.OTP_COOLDOWN);
        }
    }

    private void consumeResetOtp(UUID userId, String otpHash) {
        EmailVerificationOtpCheckResult checkResult;
        try {
            checkResult = passwordResetOtpStore.consume(userId, otpHash);
        } catch (DataAccessException | IllegalStateException exception) {
            throw new AppException(ErrorCode.OTP_VERIFICATION_UNAVAILABLE);
        }
        switch (checkResult) {
            case VALID -> { }
            case INVALID -> throw new AppException(ErrorCode.OTP_MISMATCH);
            case NOT_FOUND -> throw new AppException(ErrorCode.OTP_NOT_FOUND);
        }
    }

    private void publishResetEmail(EmailVerificationRequestedMessage message, String otpHash) {
        try {
            passwordResetEmailPublisher.publish(message);
        } catch (AppException exception) {
            if (exception.getErrorCode() == ErrorCode.EMAIL_VERIFICATION_PUBLISH_FAILED) {
                cancelResetOtp(message.getUserId(), otpHash);
            }
            throw new AppException(ErrorCode.OTP_REQUEST_UNAVAILABLE);
        } catch (RuntimeException exception) {
            throw new AppException(ErrorCode.OTP_REQUEST_UNAVAILABLE);
        }
    }

    private void cancelResetOtp(UUID userId, String otpHash) {
        try {
            passwordResetOtpStore.deleteIfCurrent(userId, otpHash);
        } catch (RuntimeException exception) {
            log.warn("Unable to clean up a password reset request for user {}", userId);
        }
    }
}
