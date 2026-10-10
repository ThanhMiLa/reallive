package com.reallive.userservice.user.adapter.kafka;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.adapter.kafka.message.EmailVerificationRequestedMessage;

public interface EmailVerificationEmailPublisher {

    /**
     * Publishes an email verification message and returns after broker acknowledgement.
     *
     * <p>Email delivery is handled asynchronously by Notification Service.</p>
     *
     * @param message the Kafka message containing the OTP and recipient information
     * @throws AppException with {@link ErrorCode#EMAIL_VERIFICATION_PUBLISH_FAILED} if
     *         publication fails before submission, or
     *         {@link ErrorCode#EMAIL_VERIFICATION_PUBLISH_UNCERTAIN} if the message may
     *         already have reached Kafka
     */
    void publish(EmailVerificationRequestedMessage message);
}
