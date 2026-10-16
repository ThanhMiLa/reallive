package com.reallive.userservice.user.service.impl;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.adapter.kafka.EmailVerificationEmailPublisher;
import com.reallive.userservice.user.adapter.kafka.message.EmailType;
import com.reallive.userservice.user.adapter.kafka.message.EmailVerificationRequestedMessage;
import com.reallive.userservice.user.adapter.redis.EmailVerificationOtpStore;
import com.reallive.userservice.user.dto.response.EmailVerificationCooldownResult;
import com.reallive.userservice.user.util.EmailVerificationOtpHasher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmailVerificationServiceImplTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final EmailVerificationOtpStore store = mock(EmailVerificationOtpStore.class);
    private final EmailVerificationEmailPublisher publisher = mock(EmailVerificationEmailPublisher.class);
    private final SecureRandom random = mock(SecureRandom.class);
    private final EmailVerificationOtpHasher hasher = new EmailVerificationOtpHasher("test-step-seven-hmac-key");
    private EmailVerificationServiceImpl service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationServiceImpl(repository, store, publisher, hasher);
        ReflectionTestUtils.setField(service, "ttlSeconds", 60L);
        ReflectionTestUtils.setField(service, "secureRandom", random);
        user = UserEntity.builder().id(UUID.randomUUID()).email("current@example.com")
                .username("otp-user").passwordHash("password-hash").build();
        when(repository.findById(user.getId())).thenReturn(Optional.of(user));
        when(random.nextInt(1_000_000)).thenReturn(12345);
        when(store.save(eq(user.getId()), anyString()))
                .thenReturn(new EmailVerificationCooldownResult(true, Duration.ZERO));
    }

    @Test
    void storesBoundHashBeforePublishingAndReturnsTheSameExpiry() {
        Instant before = Instant.now();
        var response = service.requestEmailVerificationOtp(user.getId());
        ArgumentCaptor<EmailVerificationRequestedMessage> captor = ArgumentCaptor.forClass(
                EmailVerificationRequestedMessage.class);
        var order = inOrder(store, publisher);
        order.verify(store).hasCooldown(user.getId());
        order.verify(store).save(user.getId(), hasher.hash(user.getId(), user.getEmail(), "012345"));
        order.verify(publisher).publish(captor.capture());
        var message = captor.getValue();
        assertEquals(1, message.getSchemaVersion());
        assertEquals(EmailType.EMAIL_VERIFICATION, message.getEmailType());
        assertEquals(user.getId(), message.getUserId());
        assertEquals(user.getEmail(), message.getRecipientEmail());
        assertEquals("012345", message.getOtp());
        assertNotNull(message.getEventId());
        assertNotNull(message.getChallengeId());
        assertTrue(!message.getIssuedAt().isBefore(before));
        assertEquals(Duration.ofSeconds(60), Duration.between(message.getIssuedAt(), message.getExpiresAt()));
        assertEquals(message.getExpiresAt(), response.getExpiresAt());
        assertFalse(user.isEmailVerified());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertTrue(user.getRoles().isEmpty());
        verify(repository, never()).save(any());
    }

    @Test
    void missingUserDoesNotTouchRedisOrGenerateOtp() {
        when(repository.findById(user.getId())).thenReturn(Optional.empty());
        assertError(ErrorCode.USER_NOT_FOUND);
        verifyNoInteractions(store, random, publisher);
    }

    @Test
    void requestOtpDoesNotRepeatGatewayAccountStatusCheck() {
        user.setStatus(UserStatus.BANNED);
        Assertions.assertDoesNotThrow(() -> service.requestEmailVerificationOtp(user.getId()));
        verify(publisher).publish(any());
    }

    @Test
    void verifiedEmailCannotRequestOtp() {
        user.setEmailVerified(true);
        assertError(ErrorCode.EMAIL_ALREADY_VERIFIED);
        verifyNoInteractions(store, random, publisher);
    }

    @Test
    void existingCooldownDoesNotGenerateOrSaveAnotherOtp() {
        when(store.hasCooldown(user.getId())).thenReturn(true);
        assertError(ErrorCode.OTP_COOLDOWN);
        verifyNoInteractions(random, publisher);
        verify(store, never()).save(any(), anyString());
    }

    @Test
    void losingConcurrentSaveDoesNotPublish() {
        when(store.save(eq(user.getId()), anyString()))
                .thenReturn(new EmailVerificationCooldownResult(false, Duration.ofSeconds(59)));
        assertError(ErrorCode.OTP_COOLDOWN);
        verifyNoInteractions(publisher);
        verify(store, never()).deleteIfCurrent(any(), anyString());
    }

    @Test
    void unavailableRedisBeforeGenerationReturns503() {
        when(store.hasCooldown(user.getId())).thenThrow(new RedisConnectionFailureException("test failure"));
        assertError(ErrorCode.OTP_REQUEST_UNAVAILABLE);
        verifyNoInteractions(random, publisher);
    }

    @Test
    void unavailableRedisDuringSaveDoesNotPublish() {
        when(store.save(eq(user.getId()), anyString()))
                .thenThrow(new RedisConnectionFailureException("test failure"));
        assertError(ErrorCode.OTP_REQUEST_UNAVAILABLE);
        verifyNoInteractions(publisher);
    }

    @Test
    void knownPublicationFailureWithdrawsOnlyTheCurrentHash() {
        doThrow(new AppException(ErrorCode.EMAIL_VERIFICATION_PUBLISH_FAILED)).when(publisher).publish(any());
        assertError(ErrorCode.OTP_REQUEST_UNAVAILABLE);
        verify(store).deleteIfCurrent(user.getId(), hasher.hash(user.getId(), user.getEmail(), "012345"));
    }

    @Test
    void uncertainPublicationFailureKeepsTheKey() {
        doThrow(new AppException(ErrorCode.EMAIL_VERIFICATION_PUBLISH_UNCERTAIN))
                .when(publisher).publish(any());
        assertError(ErrorCode.OTP_REQUEST_UNAVAILABLE);
        verify(store, never()).deleteIfCurrent(any(), anyString());
    }

    @Test
    void unexpectedPublicationFailureIsAlsoConservative() {
        doThrow(new IllegalStateException("unexpected failure")).when(publisher).publish(any());
        assertError(ErrorCode.OTP_REQUEST_UNAVAILABLE);
        verify(store, never()).deleteIfCurrent(any(), anyString());
    }

    @Test
    void unrelatedAppExceptionDoesNotWithdrawOtp() {
        doThrow(new AppException(ErrorCode.OTP_REQUEST_UNAVAILABLE)).when(publisher).publish(any());
        assertError(ErrorCode.OTP_REQUEST_UNAVAILABLE);
        verify(store, never()).deleteIfCurrent(any(), anyString());
    }

    @Test
    void cleanupFailureDoesNotTurnTheRequestIntoSuccess() {
        doThrow(new AppException(ErrorCode.EMAIL_VERIFICATION_PUBLISH_FAILED))
                .when(publisher).publish(any());
        doThrow(new RedisConnectionFailureException("cleanup failure"))
                .when(store).deleteIfCurrent(any(), anyString());
        assertError(ErrorCode.OTP_REQUEST_UNAVAILABLE);
    }

    @Test
    void missingHmacKeyDoesNotCreateAnOtpKeyOrPublish() {
        service = new EmailVerificationServiceImpl(repository, store, publisher, new EmailVerificationOtpHasher(""));
        ReflectionTestUtils.setField(service, "ttlSeconds", 60L);
        assertError(ErrorCode.OTP_CONFIGURATION_INVALID);
        verify(store, never()).save(any(), anyString());
        verifyNoInteractions(publisher);
    }

    @Test
    void expiryUsesTheSameConfiguredLifetimeAsRedis() {
        ReflectionTestUtils.setField(service, "ttlSeconds", 30L);
        service.requestEmailVerificationOtp(user.getId());
        ArgumentCaptor<EmailVerificationRequestedMessage> captor = ArgumentCaptor.forClass(
                EmailVerificationRequestedMessage.class);
        verify(publisher).publish(captor.capture());
        assertEquals(Duration.ofSeconds(30), Duration.between(
                captor.getValue().getIssuedAt(), captor.getValue().getExpiresAt()));
    }

    private void assertError(ErrorCode expected) {
        AppException exception = assertThrows(AppException.class,
                () -> service.requestEmailVerificationOtp(user.getId()));
        assertEquals(expected, exception.getErrorCode());
        verify(repository, never()).save(any());
    }
}
