package com.reallive.notificationservice.email.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "notification.email")
public class EmailDeliveryProperties {

    @NotBlank
    private String topic;

    @NotBlank
    private String deadLetterTopic;

    @Positive
    private int partitions = 3;

    @Positive
    private short replicas = 1;

    @Positive
    private long retentionMs = 600000;

    @Min(0)
    private long maxRetries = 2;

    @Positive
    private long retryBackoffMs = 1000;

    @Valid
    private SendGrid sendgrid = new SendGrid();

    @AssertTrue(message = "The email request and dead-letter topics must differ")
    public boolean isDeadLetterTopicDistinct() {
        return topic == null || !topic.equals(deadLetterTopic);
    }

    @Getter
    @Setter
    public static class SendGrid {

        private String apiKey;

        @NotBlank
        @Email
        private String senderEmail;

        @NotBlank
        private String senderName;

        private String webhookVerificationPublicKey;

        @AssertTrue(message = "SENDGRID_API_KEY must be configured")
        public boolean isApiKeyConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }

        @AssertTrue(message = "SENDGRID_WEBHOOK_PUBLIC_KEY must be configured")
        public boolean isWebhookKeyConfigured() {
            return webhookVerificationPublicKey != null && !webhookVerificationPublicKey.isBlank();
        }
    }
}
