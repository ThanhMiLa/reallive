package com.reallive.userservice.user.adapter.kafka.message;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

import java.time.Instant;
import java.util.UUID;

/** Kafka JSON contract; template selection belongs to Notification Service. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class EmailVerificationRequestedMessage {
    int schemaVersion;
    UUID eventId;
    UUID challengeId;
    UUID userId;
    EmailType emailType;
    String recipientEmail;

    @ToString.Exclude
    String otp;

    Instant issuedAt;
    Instant expiresAt;
}
