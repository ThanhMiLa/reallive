package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.stream.dto.response.StreamDetailsResponse;

import java.util.UUID;

/** Reads display details (channel and category summaries) for a viewable stream session. */
public interface StreamDetailsService {

    /**
     * Returns channel and category display summaries for a PUBLIC stream session in any lifecycle state.
     *
     * <p>The session snapshot is read in a short read-only transaction before querying Channel Service
     * outside the transaction. If Channel Service returns a valid batch without a matching channel or
     * category entry, the corresponding summary in the response is {@code null}.</p>
     *
     * @param sessionId identifier of the stream session to inspect
     * @return session identifiers and resolved channel/category display summaries
     * @throws AppException if the session does not exist, is not PUBLIC, or Channel Service is unavailable
     */
    StreamDetailsResponse details(UUID sessionId);
}
