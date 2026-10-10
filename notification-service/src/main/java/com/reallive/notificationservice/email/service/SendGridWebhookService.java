package com.reallive.notificationservice.email.service;

import com.reallive.notificationservice.common.exception.AppException;
import org.springframework.dao.DataAccessException;

public interface SendGridWebhookService {

    /**
     * Verifies the original signed bytes and commits correlated delivery events atomically.
     *
     * <p>Duplicate provider IDs and unrelated emails are acknowledged without insertion.
     * Delivery history does not change submission status or User Service data.
     * A persistence failure rolls back the whole batch.</p>
     *
     * @param body the original webhook request bytes
     * @param signature the Base64 ECDSA signature header
     * @param timestamp the signed timestamp header
     * @throws AppException if authentication, configuration or event validation fails
     * @throws DataAccessException if delivery history cannot be persisted
     */
    void receive(byte[] body, String signature, String timestamp);
}
