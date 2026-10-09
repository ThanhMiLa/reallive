package com.reallive.userservice.user.service.impl;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.adapter.kafka.EmailVerificationEmailPublisher;
import com.reallive.userservice.user.adapter.redis.EmailVerificationOtpStore;
import com.reallive.userservice.user.dto.request.VerifyEmailOtpRequest;
import com.reallive.userservice.user.dto.response.EmailVerificationOtpCheckResult;
import com.reallive.userservice.user.util.EmailVerificationOtpHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VerifyEmailOtpServiceTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final EmailVerificationOtpStore store = mock(EmailVerificationOtpStore.class);
    private final EmailVerificationEmailPublisher publisher = mock(EmailVerificationEmailPublisher.class);
    private final EmailVerificationOtpHasher hasher = new EmailVerificationOtpHasher("verify-test-key");
    private final VerifyEmailOtpRequest request = new VerifyEmailOtpRequest("012345");
    private EmailVerificationServiceImpl service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationServiceImpl(repository, store, publisher, hasher);
        user = UserEntity.builder().id(UUID.randomUUID()).email("current@example.com")
                .username("verify-user").passwordHash("test-hash").build();
        when(repository.findById(user.getId())).thenReturn(Optional.of(user));
        when(store.consume(user.getId(), hash())).thenReturn(EmailVerificationOtpCheckResult.VALID);
    }

    @Test
    void consumesBoundOtpBeforePersistingVerificationWithoutPublishing() {
        var response = service.verifyEmailOtp(user.getId(), request);
        var order = inOrder(repository, store);
        order.verify(repository).findById(user.getId());
        order.verify(store).consume(user.getId(), hash());
        order.verify(repository).save(user);
        assertTrue(response.isEmailVerified());
        assertTrue(user.isEmailVerified());
        assertTrue(user.getRoles().isEmpty());
        verifyNoInteractions(publisher);
    }

    @Test
    void wrongOtpDoesNotModifyOrSaveUser() {
        when(store.consume(user.getId(), hash())).thenReturn(EmailVerificationOtpCheckResult.INVALID);
        assertError(ErrorCode.OTP_MISMATCH);
        assertFalse(user.isEmailVerified());
    }

    @Test
    void missingExpiredOrConsumedOtpDoesNotModifyOrSaveUser() {
        when(store.consume(user.getId(), hash())).thenReturn(EmailVerificationOtpCheckResult.NOT_FOUND);
        assertError(ErrorCode.OTP_NOT_FOUND);
        assertFalse(user.isEmailVerified());
    }

    @Test
    void alreadyVerifiedEmailIsRejectedBeforeConsumingOtp() {
        user.setEmailVerified(true);
        assertError(ErrorCode.EMAIL_ALREADY_VERIFIED);
        verifyNoInteractions(store);
        assertTrue(user.isEmailVerified());
    }

    @Test
    void bannedUserIsRejectedBeforeConsumingOtp() {
        user.setStatus(UserStatus.BANNED);
        assertError(ErrorCode.USER_NOT_ACTIVE);
        verifyNoInteractions(store);
        assertFalse(user.isEmailVerified());
    }

    @Test
    void hashesTheCurrentDatabaseEmail() {
        user.setEmail("changed@example.com");
        when(store.consume(user.getId(), hash())).thenReturn(EmailVerificationOtpCheckResult.VALID);
        assertTrue(service.verifyEmailOtp(user.getId(), request).isEmailVerified());
        verify(store).consume(user.getId(), hasher.hash(user.getId(), "changed@example.com", "012345"));
    }

    @Test
    void missingUserDoesNotTouchRedis() {
        when(repository.findById(user.getId())).thenReturn(Optional.empty());
        assertError(ErrorCode.USER_NOT_FOUND);
        verifyNoInteractions(store);
    }

    @Test
    void redisFailureDoesNotModifyOrSaveUser() {
        when(store.consume(user.getId(), hash())).thenThrow(new RedisConnectionFailureException("test failure"));
        assertError(ErrorCode.OTP_VERIFICATION_UNAVAILABLE);
        assertFalse(user.isEmailVerified());
    }

    @Test
    void missingHmacKeyDoesNotConsumeOtpOrSaveUser() {
        service = new EmailVerificationServiceImpl(repository, store, publisher, new EmailVerificationOtpHasher(""));
        assertError(ErrorCode.OTP_CONFIGURATION_INVALID);
        verifyNoInteractions(store);
    }

    @Test
    void databaseFailureAfterConsumptionDoesNotReturnSuccess() {
        when(repository.save(user)).thenThrow(new DataIntegrityViolationException("test persistence failure"));
        assertThrows(DataIntegrityViolationException.class, () -> service.verifyEmailOtp(user.getId(), request));
        verify(store).consume(user.getId(), hash());
        verifyNoInteractions(publisher);
    }

    private String hash() {
        return hasher.hash(user.getId(), user.getEmail(), request.getOtp());
    }

    private void assertError(ErrorCode expected) {
        assertEquals(expected, assertThrows(AppException.class,
                () -> service.verifyEmailOtp(user.getId(), request)).getErrorCode());
        verify(repository, never()).save(any());
        verify(store, never()).save(any(), anyString());
        verifyNoInteractions(publisher);
    }
}
