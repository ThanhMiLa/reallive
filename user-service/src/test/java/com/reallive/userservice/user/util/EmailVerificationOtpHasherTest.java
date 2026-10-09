package com.reallive.userservice.user.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmailVerificationOtpHasherTest {

    private static final UUID USER_ID = UUID.fromString("7e94d13e-ddd9-4ec6-bbba-abbb30d55c0a");
    private static final String TEST_KEY = "test-email-otp-secret-32-bytes-minimum";
    private final EmailVerificationOtpHasher hasher = new EmailVerificationOtpHasher(TEST_KEY);

    @Test
    void matchesKnownHmacVectorAndPreservesLeadingZero() {
        assertEquals("baf668145f379f825d794ea37c70aa127e3399df8b852290a6f1c925bf8f179a",
                hasher.hash(USER_ID, "user@example.com", "012345"));
    }

    @Test
    void bindsHashToUserEmailOtpAndSecret() {
        String expected = hasher.hash(USER_ID, "user@example.com", "012345");

        assertNotEquals(expected, hasher.hash(UUID.randomUUID(), "user@example.com", "012345"));
        assertNotEquals(expected, hasher.hash(USER_ID, "other@example.com", "012345"));
        assertNotEquals(expected, hasher.hash(USER_ID, "user@example.com", "123456"));
        assertNotEquals(expected, new EmailVerificationOtpHasher("another-test-key")
                .hash(USER_ID, "user@example.com", "012345"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12345", "1234567", "12345a", "123456\n", "１２３４５６"})
    void rejectsMalformedOtp(String otp) {
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(USER_ID, "user@example.com", otp));
    }

    @Test
    void requiresSecretOnlyWhenHashingIsRequested() {
        EmailVerificationOtpHasher unconfiguredHasher = new EmailVerificationOtpHasher("");

        assertThrows(IllegalStateException.class,
                () -> unconfiguredHasher.hash(USER_ID, "user@example.com", "012345"));
    }
}
