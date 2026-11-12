package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.stream.dto.response.ChatContextResponse;
import java.util.UUID;

public interface ChatContextService {
    /**
     * Reads the authoritative session context for chat admission.
     * @param sessionId stream session identifier
     * @return session identity, channel, visibility and current lifecycle state
     * @throws com.reallive.streamservice.common.exception.AppException if the session does not exist
     */
    ChatContextResponse context(UUID sessionId);
}
