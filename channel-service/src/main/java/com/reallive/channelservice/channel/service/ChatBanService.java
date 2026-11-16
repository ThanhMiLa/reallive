package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.response.ChatBanResponse;
import com.reallive.channelservice.common.dto.PageResponse;
import java.util.UUID;

public interface ChatBanService {
    /**
     * Bans a user from channel chat after checking ownership and the existing ban.
     *
     * @param actorId authenticated caller
     * @param channelId channel to manage
     * @param userId target user
     * @return current ban state
     * @throws com.reallive.channelservice.common.exception.AppException when authorization or validation fails
     */
    ChatBanResponse.State ban(UUID actorId, UUID channelId, UUID userId);

    /**
     * Removes an active chat ban.
     *
     * @param actorId authenticated caller
     * @param channelId channel to manage
     * @param userId target user
     * @return current ban state
     * @throws com.reallive.channelservice.common.exception.AppException when the ban cannot be removed
     */
    ChatBanResponse.State unban(UUID actorId, UUID channelId, UUID userId);

    /**
     * Reads the current ban state.
     *
     * @param actorId authenticated caller
     * @param channelId channel to manage
     * @param userId target user
     * @return current ban state
     * @throws com.reallive.channelservice.common.exception.AppException when authorization fails
     */
    ChatBanResponse.State state(UUID actorId, UUID channelId, UUID userId);

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
    PageResponse<ChatBanResponse.Item> list(UUID actorId, UUID channelId, int page, int size);
    /**
     * Resolves the caller's moderation capabilities for a channel.
     *
     * @param actorId authenticated caller
     * @param channelId channel to inspect
     * @param canBan whether the caller has the ban permission
     * @param canUnban whether the caller has the unban permission
     * @param canViewBans whether the caller has the list permission
     * @return capabilities scoped to the channel
     * @throws com.reallive.channelservice.common.exception.AppException when the channel or owner is unavailable
     */
    ChatBanResponse.Context context(UUID actorId, UUID channelId, boolean canBan, boolean canUnban, boolean canViewBans);

}
