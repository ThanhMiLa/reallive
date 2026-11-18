package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.response.ChannelBanResponse;
import com.reallive.channelservice.channel.entity.enums.ChannelBanType;
import com.reallive.channelservice.common.dto.PageResponse;

import java.util.UUID;

/**
 * Manages channel bans. Callers must be authorized for the channel before reading or changing bans.
 */
public interface ChannelBanService {
    /**
     * Creates or changes an active ban for a non-owner in an active channel.
     *
     * @param actorId authorized caller recorded as the performer
     * @param channelId channel to manage
     * @param userId target user
     * @param banType requested ban level
     * @return current ban state and permitted actions
     * @throws com.reallive.channelservice.common.exception.AppException when the channel, target, or requested change is invalid
     */
    ChannelBanResponse.State banUserFromChannel(UUID actorId, UUID channelId, UUID userId, ChannelBanType banType);

    /**
     * Removes an active ban in an active channel.
     *
     * @param channelId channel to manage
     * @param userId target user
     * @return current ban state and permitted actions
     * @throws com.reallive.channelservice.common.exception.AppException when the ban cannot be removed
     */
    ChannelBanResponse.State unbanUserFromChannel(UUID channelId, UUID userId);

    /**
     * Reads the current ban state.
     *
     * @param channelId channel to manage
     * @param userId target user
     * @return current ban state and permitted actions
     * @throws com.reallive.channelservice.common.exception.AppException when the channel is unavailable or inactive
     */
    ChannelBanResponse.State getUserBanStatus(UUID channelId, UUID userId);

    /**
     * Lists active channel bans.
     *
     * @param channelId channel to manage
     * @param page zero-based page
     * @param size page size
     * @return paginated active bans
     * @throws com.reallive.channelservice.common.exception.AppException when the channel or pagination is invalid
     */
    PageResponse<ChannelBanResponse.Item> getBannedUsers(UUID channelId, int page, int size);

    /**
     * Resolves the caller's moderation capabilities for a channel.
     *
     * @param actorId authenticated caller
     * @param channelId channel to inspect
     * @return capabilities scoped to the channel
     * @throws com.reallive.channelservice.common.exception.AppException when the channel or owner is unavailable
     */
    ChannelBanResponse.Context getChannelModerationContext(UUID actorId, UUID channelId);

}
