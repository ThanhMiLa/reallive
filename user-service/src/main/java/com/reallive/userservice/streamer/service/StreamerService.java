package com.reallive.userservice.streamer.service;

import com.reallive.userservice.common.exception.AppException;

import java.util.UUID;

public interface StreamerService {

    /**
     * Returns the ID of the active streamer belonging to the supplied user without modifying data.
     *
     * @param userId the user ID whose streamer record is checked
     * @return the streamer ID when the persisted streamer status is {@code ACTIVE}
     * @throws AppException if the streamer does not exist or its status is not {@code ACTIVE}
     */
    UUID getActiveStreamerIdByUserId(UUID userId);
}
