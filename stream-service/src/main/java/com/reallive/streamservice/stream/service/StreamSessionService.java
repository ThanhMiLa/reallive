package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.stream.dto.response.StreamMetadataResponse;
import com.reallive.streamservice.stream.dto.response.StreamSessionResponse;

import java.util.UUID;

/** Provides read access to current owner session state and public session metadata. */
public interface StreamSessionService {

    /**
     * Returns viewer-facing metadata for a PUBLIC session in any lifecycle state.
     *
     * @param sessionId session to read
     * @return persisted metadata and current server time without credentials
     * @throws AppException if the session does not exist or its visibility is restricted
     */
    StreamMetadataResponse metadata(UUID sessionId);

    /**
     * Returns the owner's active non-ended session and its ingest readiness observation, or null if
     * offline.
     *
     * @param ownerUserId authenticated account identifier
     * @return active owner session metadata, or null if no LIVE/RECONNECTING session exists
     */
    StreamSessionResponse current(UUID ownerUserId);
}
