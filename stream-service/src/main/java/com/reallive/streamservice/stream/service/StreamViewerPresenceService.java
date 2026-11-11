package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.stream.dto.request.ViewerPresenceEvent;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import java.util.UUID;

public interface StreamViewerPresenceService {
    /**
     * Applies an authenticated reader event idempotently, ignoring expired or terminal events.
     * @param event validated internal event
     * @throws com.reallive.streamservice.common.exception.AppException when storage or stream state prevents processing
     */
    void accept(ViewerPresenceEvent event);

    /**
     * Reads the current viewer count without preventing metadata access on presence failures.
     * @param sessionId stream identifier
     * @param status current database status
     * @return count, zero for inactive streams, or null when presence is unavailable
     */
    Long count(UUID sessionId, StreamSessionStatus status);

    /**
     * Schedules best-effort terminal presence cleanup after the surrounding transaction commits.
     * @param sessionId stream identifier
     */
    void closeAfterCommit(UUID sessionId);
}
