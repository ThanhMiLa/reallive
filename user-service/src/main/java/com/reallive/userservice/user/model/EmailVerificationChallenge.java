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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class EmailVerificationChallenge {
    UUID challengeId;
    UUID userId;
    String email;

    @ToString.Exclude
    String otpHash;

    Instant issuedAt;
    Instant expiresAt;
}
