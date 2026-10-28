package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.stream.dto.response.StreamSessionResponse;

import java.util.UUID;

public interface StreamSessionClosureService {

    /**
     * Closes an owned session's media path before committing ENDED/CLOSED and history.
     *
     * <p>Holds the session row lock through media I/O, checks the actual source and kicks it if necessary.
     * A repeated End is a no-op; media failure rolls back DB changes and leaves the channel slot occupied.</p>
     *
     * @param sessionId session to close
     * @param ownerUserId authenticated owner; eligibility to publish is not required
     * @param reason optional history reason of at most 500 characters
     * @return committed owner metadata without credentials
     * @throws AppException if the session is missing, unowned or media closure cannot be confirmed
     */
    StreamSessionResponse end(UUID sessionId, UUID ownerUserId, String reason);

    /**
     * Closes the media path and commits the session to ENDED/CLOSED if the reconnect grace period has expired.
     *
     * <p>Acquires a row lock on the session. If the session is absent, not in RECONNECTING status,
     * or its persisted reconnect deadline has not yet arrived according to the stream clock, no media
     * operations are performed and false is returned. Otherwise, closes and verifies the media path
     * before delegating to the domain service to transition to ENDED. A media failure rolls back
     * the transaction, keeping the session in RECONNECTING.</p>
     *
     * @param sessionId session identifier whose reconnect deadline should be evaluated
     * @return true if the session was expired and closed, false otherwise
     * @throws AppException if MediaMTX communication fails during closure
     */
    boolean expireReconnectIfDue(UUID sessionId);
}
