package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.stream.dto.response.DonationContextResponse;
import java.util.UUID;

public interface DonationContextService {
    /**
     * Reads the authoritative session context for donation eligibility.
     * @param sessionId stream session identifier
     * @return session identity, channel, streamer, owner, visibility and current lifecycle state
     * @throws com.reallive.streamservice.common.exception.AppException if the session does not exist
     */
    DonationContextResponse context(UUID sessionId);
}
