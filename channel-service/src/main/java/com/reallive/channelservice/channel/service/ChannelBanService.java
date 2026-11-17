package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.response.ChannelBanResponse;
import com.reallive.channelservice.channel.entity.enums.ChannelBanType;
import com.reallive.channelservice.common.dto.PageResponse;

import java.util.UUID;

public interface ChannelBanService {
    /**
     * Creates or changes an active channel ban after checking the caller and required permissions.
     *
     * @param actorId authenticated caller
     * @param channelId channel to manage
     * @param userId target user
     * @param banType requested ban level
     * @return current ban state and permitted actions
     * @throws com.reallive.channelservice.common.exception.AppException when authorization or validation fails
     */
    ChannelBanResponse.State banUserFromChannel(UUID actorId, UUID channelId, UUID userId, ChannelBanType banType);

    /**
     * Removes an active channel ban after checking its required unban permission.
     *
     * @param actorId authenticated caller
     * @param channelId channel to manage
     * @param userId target user
     * @return current ban state and permitted actions
     * @throws com.reallive.channelservice.common.exception.AppException when the ban cannot be removed
     */
    ChannelBanResponse.State unbanUserFromChannel(UUID actorId, UUID channelId, UUID userId);

    /**
     * Reads the current ban state.
     *
     * @param actorId authenticated caller
     * @param channelId channel to manage
     * @param userId target user
     * @return current ban state and permitted actions
     * @throws com.reallive.channelservice.common.exception.AppException when authorization fails
     */
    ChannelBanResponse.State getUserBanStatus(UUID actorId, UUID channelId, UUID userId);

    /**
     * Lists active channel bans.
     *
     * @param actorId authenticated caller
     * @param channelId channel to manage
     * @param page zero-based page
     * @param size page size
     * @return paginated active bans
     * @throws com.reallive.channelservice.common.exception.AppException when authorization or pagination validation fails
     */
    PageResponse<ChannelBanResponse.Item> getBannedUsers(UUID actorId, UUID channelId, int page, int size);

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
