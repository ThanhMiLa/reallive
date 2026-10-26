package com.reallive.userservice.streamer.service;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.streamer.dto.response.StreamingEligibilityResponse;

import java.util.UUID;

public interface StreamerService {

    /**
     * Returns the ID of the active streamer belonging to the supplied user without modifying data.
     *
     * @param userId the user ID whose streamer record is checked
     * @return the streamer ID when the persisted streamer status is {@code ACTIVE}
     * @throws AppException if the user does not exist, or if the streamer does not exist
     *                      or its status is not {@code ACTIVE}
     */
    UUID getActiveStreamerIdByUserId(UUID userId);
    /**
     * Resolves a verified-email user and their ACTIVE streamer without changing account state.
     *
     * @param userId the authenticated user identifier supplied by a trusted service
     * @return matching user and streamer identifiers when streaming eligibility checks pass
     * @throws AppException if the user/streamer is missing, email is unverified or streamer is inactive
     */
    StreamingEligibilityResponse getStreamingEligibility(UUID userId);
}
