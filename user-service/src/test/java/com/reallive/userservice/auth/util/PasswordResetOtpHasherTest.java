package com.reallive.userservice.auth.util;

import com.reallive.userservice.user.util.EmailVerificationOtpHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordResetOtpHasherTest {

    private static final UUID USER_ID = UUID.fromString("7e94d13e-ddd9-4ec6-bbba-abbb30d55c0a");
    private static final String TEST_KEY = "test-email-otp-secret-32-bytes-minimum";
    private final PasswordResetOtpHasher hasher = new PasswordResetOtpHasher(TEST_KEY);

    @Test
    void bindsHashToUserEmailOtpAndSecret() {
        String expected = hasher.hash(USER_ID, "user@example.com", "012345");

        assertNotEquals(expected, hasher.hash(UUID.randomUUID(), "user@example.com", "012345"));
        assertNotEquals(expected, hasher.hash(USER_ID, "other@example.com", "012345"));
        assertNotEquals(expected, hasher.hash(USER_ID, "user@example.com", "123456"));
        assertNotEquals(expected, new PasswordResetOtpHasher("another-test-key")
                .hash(USER_ID, "user@example.com", "012345"));
    }

    @Test
    void producesDifferentHashFromEmailVerificationForPurposeSeparation() {
        EmailVerificationOtpHasher emailHasher = new EmailVerificationOtpHasher(TEST_KEY);
        String emailVerificationHash = emailHasher.hash(USER_ID, "user@example.com", "012345");
        String passwordResetHash = hasher.hash(USER_ID, "user@example.com", "012345");

        assertNotEquals(emailVerificationHash, passwordResetHash);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12345", "1234567", "12345a", "123456\n", "１２３４５６"})
    void rejectsMalformedOtp(String otp) {
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(USER_ID, "user@example.com", otp));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsMalformedEmail(String email) {
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(USER_ID, email, "123456"));
    }

    @Test
    void rejectsNullUserId() {
        assertThrows(NullPointerException.class, () -> hasher.hash(null, "user@example.com", "123456"));
    }

    @Test
    void requiresSecretOnlyWhenHashingIsRequested() {
        PasswordResetOtpHasher unconfiguredHasher = new PasswordResetOtpHasher("");

        assertThrows(IllegalStateException.class,
                () -> unconfiguredHasher.hash(USER_ID, "user@example.com", "012345"));
    }
}
