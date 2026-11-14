package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;

import java.util.UUID;

/**
 * Handles background expiration and forced closure of playback sessions when reconnect grace
 * expires.
 */
public interface StreamSessionClosureService {

    /**
     * Closes the playback media path and transitions a RECONNECTING session to ENDED if its grace
     * period has expired under strict channel-config-before-session locking.
     *
     * @param sessionId session identifier whose reconnect deadline should be evaluated
     * @return true if the session was expired and ended, false otherwise
     * @throws AppException if MediaMTX communication fails during playback closure verification
     */
    boolean expireReconnectIfDue(UUID sessionId);
}
