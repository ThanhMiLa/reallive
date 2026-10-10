package com.reallive.notificationservice.email.util;

import com.reallive.notificationservice.email.client.sendgrid.dto.request.SendGridMailRequest;
import com.reallive.notificationservice.email.configuration.EmailDeliveryProperties;
import com.reallive.notificationservice.email.dto.message.EmailVerificationRequestedMessage;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SendGridMailRequestBuilder {

    /**
     * Builds the provider payload from a validated message and the stored template.
     *
     * @param message the email request containing recipient, OTP and event identity
     * @param templateId the template selected for this notification
     * @param sender the configured sender details
     * @return the prepared Mail Send payload, preserving the OTP's leading zeros
     */
    public static SendGridMailRequest build(EmailVerificationRequestedMessage message, String templateId,
            EmailDeliveryProperties.SendGrid sender) {
        long seconds = Duration.between(message.getIssuedAt(), message.getExpiresAt()).getSeconds();
        int minutes = (int) Math.min(Integer.MAX_VALUE, Math.max(1, Math.ceil(seconds / 60.0)));
        return SendGridMailRequest.builder()
                .from(SendGridMailRequest.EmailAddress.builder().email(sender.getSenderEmail())
                        .name(sender.getSenderName()).build()).templateId(templateId)
                .personalizations(List.of(SendGridMailRequest.Personalization.builder()
                        .to(List.of(SendGridMailRequest.EmailAddress.builder().email(message.getRecipientEmail()).build()))
                        .dynamicTemplateData(SendGridMailRequest.OtpTemplateData.builder()
                                .otp(message.getOtp()).expiresInMinutes(minutes).build())
                        .customArgs(Map.of("eventId", message.getEventId().toString())).build())).build();
    }
}
