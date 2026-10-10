package com.reallive.userservice.auth.adapter.kafka;

import com.reallive.userservice.user.adapter.kafka.message.EmailVerificationRequestedMessage;

public interface PasswordResetEmailPublisher {

    /**
     * Publishes a password reset email request event to Kafka for asynchronous delivery.
     *
     * @param message the contract payload containing user ID, recipient email, and OTP
     */
    void publish(EmailVerificationRequestedMessage message);
}
