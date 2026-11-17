package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;

public interface StreamIngestRecoveryService {

    /**
     * Ensures the current ingest path exists for every active channel with a stream key.
     *
     * <p>Rechecks each configuration under its lifecycle lock so concurrent key replacement or
     * blocking cannot restore a retired path. Repeated calls preserve keys and existing paths;
     * playback paths and broadcast sessions are not restored or modified.</p>
     *
     * @return the number of active channel paths successfully ensured
     * @throws AppException if MediaMTX is unavailable or a path cannot be verified; paths already
     *         ensured remain configured and the entire request can safely be retried
     */
    int restoreIngestPaths();
}
