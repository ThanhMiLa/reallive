package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.common.exception.AppException;

import java.util.UUID;

public interface ChannelFollowService {

    /**
     * Creates a follow from the authenticated user to one active, non-deleted channel.
     *
     * <p>The composite primary key rejects duplicate follows, including concurrent requests.
     * The follow and an atomic increment of the channel's follower counter share one transaction.
     * The initial channel read does not acquire an explicit row lock.
     * No remote service is called.</p>
     *
     * @param userId the required user UUID from the authenticated principal
     * @param channelId the required UUID of the single channel to follow
     * @return {@code true} when the follow is created
     * @throws AppException if the user ID or channel ID is missing, the channel is missing,
     *                      soft-deleted or inactive, the user owns or already follows the channel,
     *                      or the follower counter cannot be updated
     */
    boolean followChannel(UUID userId, UUID channelId);

    /**
     * Removes the authenticated user's existing follow from one non-deleted channel.
     *
     * <p>Any channel status is allowed, including banned channels. A delete affecting no rows
     * is rejected, including when another concurrent request already removed the follow.
     * The removal and an atomic decrement of the channel's follower counter share one transaction.
     * The initial channel read does not acquire an explicit row lock.
     * No remote service is called.</p>
     *
     * @param userId the required user UUID from the authenticated principal
     * @param channelId the required UUID of the single channel to unfollow
     * @return {@code true} when the follow is removed
     * @throws AppException if the user ID or channel ID is missing, the channel is missing
     *                      or soft-deleted, the user does not currently follow the channel,
     *                      or the follower counter cannot be updated without becoming negative
     */
    boolean unfollowChannel(UUID userId, UUID channelId);
}
