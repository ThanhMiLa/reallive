package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.request.CreateChannelRequest;
import com.reallive.channelservice.channel.dto.request.UpdateChannelRequest;
import com.reallive.channelservice.channel.dto.response.ChannelResponse;
import com.reallive.channelservice.common.exception.AppException;

import java.util.UUID;

public interface ChannelService {

    /**
     * Creates an active channel with a deterministic slug for the supplied streamer.
     *
     * <p>An existing channel for the streamer, including a soft-deleted record, is rejected without changing its data.
     * Concurrent inserts are protected by database uniqueness constraints. Streamer existence
     * is not verified with User Service. Creation joins the current local transaction when present.</p>
     *
     * @param request the streamer UUID, channel name and optional HTTP/HTTPS avatar URL
     * @return {@code true} when the saved channel has an ID, otherwise {@code false}
     * @throws AppException if a channel already exists or insertion violates a database constraint
     */
    boolean createChannel(CreateChannelRequest request);

    /**
     * Updates the active channel owned by the supplied user and replaces its assigned categories.
     *
     * <p>User Service must resolve an active streamer for the user before any local changes are made.
     * The channel must be active and not deleted. Requested categories must exist and be active;
     * duplicate category IDs are collapsed. Empty descriptions and empty banner files clear those values.
     * Nonempty banner files must be JPEG, PNG or WebP images within the size and pixel limits;
     * uploads are stored in R2 and only their public URL is persisted. Channel updates are serialized
     * by a row lock and profile/category changes commit atomically. Cleanup of the previous managed
     * banner after commit, or the new upload after rollback, is best effort.</p>
     *
     * @param userId the user ID extracted from the authenticated principal
     * @param request the validated description, required banner file and nonempty category list
     * @return the updated channel profile and distinct assigned category IDs
     * @throws AppException if the streamer or channel is missing or inactive, User Service is
     *                      unavailable, a requested category is missing or inactive, or banner validation,
     *                      storage configuration or upload fails
     */
    ChannelResponse updateChannel(UUID userId, UpdateChannelRequest request);
}
