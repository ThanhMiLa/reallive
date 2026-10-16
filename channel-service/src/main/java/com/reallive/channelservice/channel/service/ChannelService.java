package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.request.ChannelListRequest;
import com.reallive.channelservice.channel.dto.request.CreateChannelRequest;
import com.reallive.channelservice.channel.dto.request.UpdateChannelProfileRequest;
import com.reallive.channelservice.channel.dto.request.UpdateChannelRequest;
import com.reallive.channelservice.channel.dto.response.ChannelResponse;
import com.reallive.channelservice.channel.dto.response.ChannelListResponse;
import com.reallive.channelservice.common.dto.PageResponse;
import com.reallive.channelservice.common.exception.AppException;

import java.util.UUID;

public interface ChannelService {

    /**
     * Retrieves a filtered page of active, non-deleted channels for public discovery.
     *
     * <p>An optional category filter matches membership in any selected active category. Missing or
     * inactive categories contribute no matches; an omitted category set disables category filtering.
     * A supplied category set must contain at least one non-null UUID.
     * An optional keyword matches a literal substring of the name,
     * description or slug, ignoring case. All supplied filters are combined with AND.
     * The required following flag selects channels currently followed ({@code true}) or not followed
     * ({@code false}) by the authenticated user, defaulting to {@code false} when omitted from the request.
     * Blank keywords disable text filtering. Channels are ordered by the requested follower-count
     * direction (descending by default), then creation time and ID descending for ties.
     * Follower totals are read from the stored channel counter without counting follower records.
     * An authenticated user ID is required. No remote service call is required,
     * and no data is changed.</p>
     *
     * @param userId the required authenticated user UUID
     * @param request the validated optional nonempty set of category UUIDs, keyword of at most 255 characters,
     *                non-null following flag, follower sort direction, zero-based page and page size from 1 through 100
     * @return the channel summaries and pagination totals; content is empty when the page has no results
     * @throws AppException if the page offset exceeds {@link Integer#MAX_VALUE} or the authenticated user ID is missing
     */
    PageResponse<ChannelListResponse> getChannels(UUID userId, ChannelListRequest request);

    /**
     * Creates an active channel with a deterministic slug for the supplied streamer and owner user.
     *
     * <p>An existing channel for the streamer, including a soft-deleted record, is rejected without changing its data.
     * Concurrent inserts are protected by database uniqueness constraints. The supplied owner user UUID
     * is required and persisted without verifying its mapping to the streamer with User Service.
     * Creation joins the current local transaction when present.</p>
     *
     * @param request the streamer UUID, owner user UUID, channel name and optional HTTP/HTTPS avatar URL
     * @return {@code true} when the saved channel has an ID, otherwise {@code false}
     * @throws AppException if the owner user UUID is missing, a channel already exists,
     *                      or insertion violates a database constraint
     */
    boolean createChannel(CreateChannelRequest request);

    /**
     * Synchronizes the supplied name and avatar URL of the streamer's non-deleted channel.
     *
     * <p>Null request fields preserve their current values, and a request with both fields null
     * leaves the channel unchanged. Supplied fields must not be empty or blank; names are stripped
     * of leading and trailing whitespace. Only the supplied fields and persistence audit timestamp
     * are changed. Channel status does not
     * restrict this internal synchronization. Updates are serialized by a row lock and join the
     * current local transaction when present. No file is uploaded or deleted, and User Service
     * is not called.</p>
     *
     * @param streamerId the streamer ID owning the channel, not the user ID
     * @param request the validated optional name (at most 150 characters) and HTTP/HTTPS avatar URL
     *                (at most 500 characters)
     * @return {@code true} when the request is applied, including a request with both fields null
     * @throws AppException if the channel is missing or soft-deleted
     */
    boolean updateChannelProfile(UUID streamerId, UpdateChannelProfileRequest request);

    /**
     * Updates the supplied profile fields and categories of the active channel owned by the user.
     *
     * <p>User Service must resolve an active streamer for the user before any local changes are made.
     * The channel must be active and not deleted. Null request fields preserve their current values.
     * A supplied category list must be nonempty and its categories must exist and be active;
     * duplicate category IDs are collapsed. Supplied descriptions must not be blank and supplied
     * banner files must not be empty. Banner files must be JPEG, PNG or WebP images within the size and pixel limits;
     * uploads are stored in R2 and only their public URL is persisted. Channel updates are serialized
     * by a row lock and profile/category changes commit atomically. Cleanup of the previous managed
     * banner after commit, or the new upload after rollback, is best effort.</p>
     *
     * @param userId the user ID extracted from the authenticated principal
     * @param request the validated optional description, banner file and category list
     * @return the updated channel profile and distinct assigned category IDs
     * @throws AppException if the streamer or channel is missing or inactive, User Service is
     *                      unavailable, a requested category is missing or inactive, or banner validation,
     *                      storage configuration or upload fails
     */
    ChannelResponse updateChannel(UUID userId, UpdateChannelRequest request);
}
