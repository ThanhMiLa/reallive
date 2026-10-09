package com.reallive.userservice.user.adapter.kafka;

import com.reallive.userservice.user.dto.message.EmailVerificationRequestedMessage;
import com.reallive.userservice.user.exception.EmailVerificationPublishException;

public interface EmailVerificationEmailPublisher {

    /**
     * Publishes an email verification message and returns after broker acknowledgement.
     *
     * <p>Email delivery is handled asynchronously by Notification Service.</p>
     *
     * @param message the Kafka message containing the OTP and recipient information
     * @throws EmailVerificationPublishException if publication fails; its uncertainty flag
     *         indicates whether the message may already have reached Kafka
     */
    void publish(EmailVerificationRequestedMessage message);
}
