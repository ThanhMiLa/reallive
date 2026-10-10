package com.reallive.notificationservice.email.service;

import com.reallive.notificationservice.common.exception.AppException;
import com.reallive.notificationservice.email.dto.message.EmailVerificationRequestedMessage;

public interface EmailVerificationDeliveryService {

    /**
     * Submits a valid, unexpired email request after durably claiming its event.
     *
     * <p>Completed duplicates do not call the provider. Ambiguous submissions are not resent.</p>
     *
     * @param message the validated Kafka request containing the transient OTP
     * @throws AppException if submission needs retry, recovery, or unavailable history prevents processing
     */
    void deliver(EmailVerificationRequestedMessage message);
}
