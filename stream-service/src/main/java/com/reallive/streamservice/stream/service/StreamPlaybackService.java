package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.stream.dto.response.StreamPlaybackAccessResponse;

import java.util.UUID;

/** Issues viewer playback credentials for sessions that are currently watchable. */
public interface StreamPlaybackService {

    /**
     * Grants the authenticated viewer temporary HLS access to a PUBLIC, LIVE session.
     *
     * <p>The session is re-read under a row lock in a transaction owned by this use case, so an
     * End committed before the lock is observed yields no credential. On success the returned URL
     * contains no token; the Read JWT must be sent through the Authorization header.</p>
     *
     * @param sessionId session the viewer wants to watch
     * @param viewerUserId authenticated account requesting playback access
     * @return playback URL, Read JWT and its expiry for the requested session
     * @throws AppException if the session is absent, not PUBLIC, or not currently LIVE
     */
    StreamPlaybackAccessResponse access(UUID sessionId, UUID viewerUserId);
}
