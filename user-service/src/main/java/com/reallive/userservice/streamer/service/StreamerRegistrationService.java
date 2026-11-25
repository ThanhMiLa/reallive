package com.reallive.userservice.streamer.service;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.streamer.dto.response.BecomeStreamerResponse;

import java.util.UUID;

public interface StreamerRegistrationService {
    /**
     * Registers an authenticated user with a verified email as an active streamer and adds the STREAMER role.
     *
     * <p>Eligibility is read from the database after locking the user row. Existing roles are preserved.
     * A user with the STREAMER role or any streamer record cannot register again, regardless of its status.
     * Committed roles are synchronized to Redis before success; failed publication leaves a durable retry task.
     * The role, streamer record and authentication synchronization task commit atomically in User Service.
     * No other service is called; channel creation is a separate operation.</p>
     *
     * @param userId the user ID extracted from the authenticated principal
     * @return the persisted streamer ID, user ID, active status and current database role names
     * @throws AppException if the user is missing, has an unverified email, is already registered,
     *                      the configured STREAMER role is missing, or authentication state publication fails
     */
    BecomeStreamerResponse becomeStreamer(UUID userId);
}
