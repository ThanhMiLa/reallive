package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.stream.adapter.redis.ViewerScanPage;
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
     * Scans one page of active viewer account IDs for a session without mutating presence.
     *
     * <p>Inactive sessions ({@code READY} or {@code ENDED}) immediately return a closed scan page.
     * When Redis returns empty hash-table buckets before finishing, scanning advances for a bounded
     * number of rounds.</p>
     *
     * @param sessionId stream session identifier
     * @param status current persisted stream status
     * @param cursor non-negative Redis {@code SSCAN} cursor
     * @param count requested step hint passed to Redis {@code SSCAN}
     * @return scan result containing readiness status, next cursor, and scanned account IDs
     * @throws com.reallive.streamservice.common.exception.AppException if presence is disabled,
     *         not active, or unavailable
     */
    ViewerScanPage scan(UUID sessionId, StreamSessionStatus status, String cursor, int count);

    /**
     * Schedules best-effort terminal presence cleanup after the surrounding transaction commits.
     * @param sessionId stream identifier
     */
    void closeAfterCommit(UUID sessionId);
}
