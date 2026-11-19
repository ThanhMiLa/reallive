package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.stream.dto.response.StreamViewerPageResponse;
import java.util.UUID;

/** Lists currently watching viewer profiles for a stream session owned by the requester. */
public interface StreamViewerListService {

    /**
     * Returns one cursor-paginated slice of active viewers enriched with user profile summaries.
     *
     * @param sessionId stream session identifier
     * @param requesterUserId authenticated account identifier requesting the viewer list
     * @param cursor non-negative base-10 Redis scan cursor ({@code "0"} starts a new scan)
     * @param size requested scan step count in the range {@code 1..50}
     * @return viewer items, next scan cursor when more buckets remain, and {@code hasMore} flag
     * @throws AppException if request parameters are invalid, the caller is unauthenticated, the
     *         session is missing or owned by another user, presence is unavailable, or profile
     *         lookup fails
     */
    StreamViewerPageResponse list(UUID sessionId, UUID requesterUserId, String cursor, int size);
}
