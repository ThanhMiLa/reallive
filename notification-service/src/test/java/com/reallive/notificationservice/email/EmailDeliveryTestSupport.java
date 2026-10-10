package com.reallive.notificationservice.email;

import com.reallive.notificationservice.email.configuration.EmailDeliveryProperties;
import com.reallive.notificationservice.email.dto.message.EmailType;
import com.reallive.notificationservice.email.dto.message.EmailVerificationRequestedMessage;

import java.time.Instant;
import java.util.UUID;

public final class EmailDeliveryTestSupport {

    private EmailDeliveryTestSupport() {
    }

    public static EmailVerificationRequestedMessage message(Instant issuedAt) {
        return EmailVerificationRequestedMessage.builder().schemaVersion(1).eventId(UUID.randomUUID())
                .challengeId(UUID.randomUUID()).userId(UUID.randomUUID()).emailType(EmailType.EMAIL_VERIFICATION)
                .recipientEmail("recipient@example.com").otp("012345")
                .issuedAt(issuedAt).expiresAt(issuedAt.plusSeconds(60)).build();
    }

    public static EmailDeliveryProperties properties() {
        var properties = new EmailDeliveryProperties();
        properties.getSendgrid().setApiKey("test-sendgrid-key");
        properties.getSendgrid().setSenderEmail("sender@example.com");
        properties.getSendgrid().setSenderName("RealLive");
        properties.getSendgrid().setEmailVerificationTemplateId("d-test-template");
        return properties;
    }
}
