package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.stream.dto.response.StreamSessionResponse;

import java.util.UUID;

public interface StreamSessionClosureService {

    /**
     * Closes an owned session's media path before committing ENDED/CLOSED and history.
     * Holds the session row lock through media I/O, checks the actual source and kicks it if necessary.
     * A repeated End is a no-op; media failure rolls back DB changes and leaves the channel slot occupied.
     * The lifecycle HTTP endpoint is introduced in phase 4.
     *
     * @param sessionId session to close
     * @param ownerUserId authenticated owner; eligibility to publish is not required
     * @param reason optional history reason of at most 500 characters
     * @return committed owner metadata without credentials
     * @throws AppException if the session is missing, unowned or media closure cannot be confirmed
     */
    StreamSessionResponse end(UUID sessionId, UUID ownerUserId, String reason);
}
