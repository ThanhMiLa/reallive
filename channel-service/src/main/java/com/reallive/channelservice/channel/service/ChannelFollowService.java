package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.common.exception.AppException;

import java.util.UUID;

public interface ChannelFollowService {

    /**
     * Creates a follow from the authenticated user to one active, non-deleted channel.
     *
     * <p>The composite primary key rejects duplicate follows, including concurrent requests.
     * The channel is read without an explicit row lock and its follower counter is not updated.
     * No remote service is called.</p>
     *
     * @param userId the required user UUID from the authenticated principal
     * @param channelId the required UUID of the single channel to follow
     * @return {@code true} when the follow is created
     * @throws AppException if the user ID or channel ID is missing, the channel is missing,
     *                      soft-deleted or inactive, or the user already follows the channel
     */
    boolean followChannel(UUID userId, UUID channelId);

    /**
     * Removes the authenticated user's existing follow from one non-deleted channel.
     *
     * <p>Any channel status is allowed, including banned channels. A delete affecting no rows
     * is rejected, including when another concurrent request already removed the follow.
     * The channel is read without an explicit row lock and its follower counter is not updated.
     * No remote service is called.</p>
     *
     * @param userId the required user UUID from the authenticated principal
     * @param channelId the required UUID of the single channel to unfollow
     * @return {@code true} when the follow is removed
     * @throws AppException if the user ID or channel ID is missing, the channel is missing
     *                      or soft-deleted, or the user does not currently follow the channel
     */
    boolean unfollowChannel(UUID userId, UUID channelId);
}
