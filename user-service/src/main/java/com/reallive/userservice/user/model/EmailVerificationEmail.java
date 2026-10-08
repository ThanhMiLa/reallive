package com.reallive.userservice.user.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

import java.time.Instant;
import java.util.UUID;

/** Internal publishing input; the Kafka wire contract is defined separately. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class EmailVerificationEmail {
    UUID eventId;
    UUID challengeId;
    UUID userId;
    String recipientEmail;

    @ToString.Exclude
    String otp;

    Instant issuedAt;
    Instant expiresAt;
}
