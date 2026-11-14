package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.stream.dto.request.SaveStreamSetupRequest;
import com.reallive.streamservice.stream.dto.response.StreamKeyResponse;
import com.reallive.streamservice.stream.dto.response.StreamSetupResponse;

import java.util.UUID;

public interface StreamSetupService {
    /**
     * Initializes channel settings on first visit without issuing a key or changing media paths.
     *
     * @param ownerUserId authenticated owner ID
     * @return channel settings without credentials
     * @throws AppException if the owner is ineligible or channel context cannot be loaded
     */
    StreamSetupResponse getOrCreateSetup(UUID ownerUserId);

    /**
     * Saves title/category and updates the active broadcast, preserving its existing ingest key.
     *
     * @param ownerUserId authenticated owner ID
     * @param request validated stream settings
     * @return saved settings without credentials
     * @throws AppException if ownership or eligibility checks fail
     */
    StreamSetupResponse saveSetup(UUID ownerUserId, SaveStreamSetupRequest request);

    /**
     * Synchronously replaces any old ingest path, ends the current broadcast and returns a new key
     * once. Tokens are not persisted; another request always replaces the previous key.
     *
     * @param ownerUserId authenticated owner ID
     * @return OBS credentials for this issuance only
     * @throws AppException if settings are incomplete, eligibility fails or MediaMTX is unavailable
     */
    StreamKeyResponse issueStreamKey(UUID ownerUserId);
}
