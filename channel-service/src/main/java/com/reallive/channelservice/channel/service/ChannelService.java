package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.request.FollowingChannelListRequest;
import com.reallive.channelservice.channel.dto.request.UnfollowedChannelListRequest;
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
     * Retrieves active, non-deleted channels followed by the authenticated user, excluding their own channels.
     *
     * <p>Exactly one business sort is applied: follower count (descending by default) or the current
     * user's follow timestamp, in either direction. Creation time and ID descending break ties.
     * Keyword, category and interest filtering are not supported. Pagination totals include the same
     * eligibility and ownership rules. No remote service is called and no data is changed.</p>
     *
     * @param userId the required authenticated user UUID
     * @param request the validated single sort criterion, direction, zero-based page and page size from 1 to 100
     * @return the channel summaries and pagination totals, including an empty page when no channels match
     * @throws AppException if the identity is missing or the page offset exceeds the supported integer range
     */
    PageResponse<ChannelListResponse> getFollowingChannels(UUID userId, FollowingChannelListRequest request);

    /**
     * Retrieves active, non-deleted channels not followed by the authenticated user, excluding their own channels.
     *
     * <p>Supplied keyword and active-category filters combine with AND; categories use any-match.
     * Null or blank keywords disable text filtering. With either filter present, only matching
     * channels are returned, ordered by follower count descending without reading user interests.
     * With no filters, User Service supplies interests and matching channels precede all other
     * eligible channels. Interests never exclude channels. Empty interests or a temporary upstream
     * outage use follower order alone. Each group uses follower count descending, then creation time
     * and ID descending. Ordering precedes pagination; totals include all eligible channels.
     * Legacy channels with a null owner remain eligible. This operation changes no data.</p>
     *
     * @param userId the required authenticated user UUID
     * @param request the validated optional filters, DESC direction, zero-based page and page size from 1 to 100
     * @return the channel summaries and pagination totals, including an empty page when filters have no matches
     * @throws AppException if the identity or offset is invalid, the user is missing, or the upstream response is invalid
     */
    PageResponse<ChannelListResponse> getUnfollowedChannels(UUID userId, UnfollowedChannelListRequest request);

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
