package com.reallive.streamservice.stream.adapter.redis;

import java.util.Map;
import java.util.UUID;

/** Stores account-level viewer presence independently of stream lifecycle authorization. */
public interface StreamViewerPresenceStore {

    /**
     * Atomically records an active HLS reader and its owning account.
     * Missing state starts in recovery; closed readers and closed sessions cannot be joined.
     *
     * @param sessionId stream session identifier
     * @param userId authenticated viewer account identifier
     * @param readerId MediaMTX HLS session identifier
     */
    void join(UUID sessionId, UUID userId, UUID readerId);

    /**
     * Atomically removes one reader and removes its viewer only after the last reader has left.
     * Unknown readers still leave a tombstone that prevents a delayed join.
     *
     * @param sessionId stream session identifier
     * @param readerId reader to close
     */
    void leave(UUID sessionId, UUID readerId);

    /**
     * Reads the account count and readiness in one Redis operation.
     *
     * @param sessionId stream session identifier
     * @return account count, zero for closed sessions, or null for missing/recovering state
     */
    Long count(UUID sessionId);

    /**
     * Fences future joins and recovery jobs, then removes reader/account data in bounded batches.
     * A failed cleanup preserves the closed marker and index so cleanup can be retried.
     *
     * @param sessionId stream session identifier
     */
    void close(UUID sessionId);

    /**
     * Starts a new recovery generation without discarding existing reader memberships.
     *
     * @param sessionId stream session identifier
     * @return recovery generation, or null when the session is closed
     */
    UUID beginRecovery(UUID sessionId);

    /**
     * Publishes a recovered count only if the same recovery generation remains current.
     * Missing or closed state is never recreated by this operation.
     *
     * @param sessionId stream session identifier
     * @param generation generation returned by beginRecovery
     * @return true when the generation was made active, otherwise false
     */
    boolean completeRecovery(UUID sessionId, UUID generation);

    /**
     * Scans the current reader-to-account index for reconciliation.
     *
     * @param sessionId stream session identifier
     * @return a detached map from reader identifiers to viewer account identifiers
     */
    Map<UUID, UUID> readers(UUID sessionId);

    /**
     * Records a media observation only while its recovery generation is still current.
     * Closed-reader tombstones also apply to observations.
     *
     * @param sessionId stream session identifier
     * @param userId authenticated viewer account identifier
     * @param readerId observed HLS reader identifier
     * @param generation expected current recovery generation
     */
    void observe(UUID sessionId, UUID userId, UUID readerId, UUID generation);
}
