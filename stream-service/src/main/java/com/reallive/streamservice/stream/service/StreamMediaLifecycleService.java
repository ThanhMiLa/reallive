package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.stream.dto.MediaIngestEventCommand;
import com.reallive.streamservice.stream.dto.response.MediaEventResponse;

/** Synchronously applies verified MediaMTX ingest lifecycle webhooks. */
public interface StreamMediaLifecycleService {

    /**
     * Creates or resumes a session for the current ingest source after verifying eligibility and
     * playback readiness.
     *
     * @param command verified ingest publisher event details
     * @return applied or ignored result; ONLINE can create the session on first publish
     * @throws AppException if validation or a required downstream call fails
     */
    MediaEventResponse onOnline(MediaIngestEventCommand command);

    /**
     * Closes playback for the matching disconnected source and starts the reconnect grace period.
     *
     * @param command verified ingest offline event details
     * @return applied transition or ignored stale/duplicate event; session ID can be absent
     * @throws AppException if validation or a required downstream call fails
     */
    MediaEventResponse onOffline(MediaIngestEventCommand command);
}
