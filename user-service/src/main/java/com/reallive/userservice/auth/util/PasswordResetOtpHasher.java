package com.reallive.userservice.auth.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Component
public class PasswordResetOtpHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private final String hmacKey;

    public PasswordResetOtpHasher(@Value("${email-verification.otp.hmac-key:}") String hmacKey) {
        this.hmacKey = hmacKey;
    }

    /** Binds the six-digit OTP to the user identity, email, and password-reset purpose. */
    public String hash(UUID userId, String email, String otp) {
        Objects.requireNonNull(userId, "User ID is required");
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        if (otp == null || !otp.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("OTP must contain six ASCII digits");
        }
        if (hmacKey == null || hmacKey.isBlank()) {
            throw new IllegalStateException("OTP_HMAC_KEY must be configured before hashing an OTP");
        }
        String payload = "password-reset\n" + userId + "\n" + email + "\n" + otp;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to hash password reset OTP", exception);
        }
    }
}
