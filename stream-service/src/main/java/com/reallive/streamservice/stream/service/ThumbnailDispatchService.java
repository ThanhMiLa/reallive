package com.reallive.streamservice.stream.service;

public interface ThumbnailDispatchService {

    /**
     * Expires overdue jobs, prepares due sessions and dispatches one bounded batch to the media worker.
     *
     * <p>Jobs are committed before any credential is signed or HTTP request is sent. A failed request
     * leaves jobs pending for a later scheduler pass.</p>
     *
     * @return the number of jobs included in the outbound batch, possibly {@code 0}
     */
    int dispatchDueJobs();
}
