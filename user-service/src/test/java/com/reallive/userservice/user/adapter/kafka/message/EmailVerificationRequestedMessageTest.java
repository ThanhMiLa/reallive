package com.reallive.userservice.user.adapter.kafka.message;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EmailVerificationRequestedMessageTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void serializesToSharedKafkaContract() throws Exception {
        String json = objectMapper.writeValueAsString(message());

        assertEquals(objectMapper.readTree(contractJson()), objectMapper.readTree(json));
    }

    @Test
    void deserializesSharedKafkaContract() throws Exception {
        EmailVerificationRequestedMessage decoded = objectMapper.readValue(
                contractJson(), EmailVerificationRequestedMessage.class);

        assertEquals(message(), decoded);
    }

    @Test
    void excludesOtpFromToString() {
        assertFalse(message().toString().contains("012345"));
    }

    private String contractJson() throws Exception {
        Path contract = Path.of(System.getProperty("basedir", "."),
                "../docs/contracts/kafka/email-verification-requested.v1.json");
        return Files.readString(contract);
    }

    private EmailVerificationRequestedMessage message() {
        return EmailVerificationRequestedMessage.builder()
                .schemaVersion(1)
                .eventId(UUID.fromString("d17d3b74-4ec5-4e81-bfe3-a7adbf292311"))
                .challengeId(UUID.fromString("58c7d687-7219-4adc-8821-e3e82e796b02"))
                .userId(UUID.fromString("7e94d13e-ddd9-4ec6-bbba-abbb30d55c0a"))
                .emailType(EmailType.EMAIL_VERIFICATION)
                .recipientEmail("user@example.com")
                .otp("012345")
                .issuedAt(Instant.parse("2026-10-09T03:00:00Z"))
                .expiresAt(Instant.parse("2026-10-09T03:01:00Z"))
                .build();
    }
}
