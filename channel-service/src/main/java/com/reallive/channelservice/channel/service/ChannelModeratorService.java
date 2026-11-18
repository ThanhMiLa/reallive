package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.common.exception.AppException;

import java.util.UUID;

public interface ChannelModeratorService {

    /**
     * Assigns an active user as moderator of an active channel owned by the caller.
     *
     * @param actorId authenticated user who owns the channel
     * @param channelId channel receiving the assignment
     * @param userId user receiving moderation access
     * @throws AppException if the channel cannot be managed, the user is ineligible or already
     *                      assigned, or the user account cannot be verified
     */
    void assign(UUID actorId, UUID channelId, UUID userId);

    /**
     * Removes an existing moderator assignment from the caller's active channel.
     *
     * @param actorId authenticated user who owns the channel
     * @param channelId channel whose assignment is removed
     * @param userId user whose moderation access is revoked
     * @throws AppException if the channel cannot be managed or the assignment does not exist
     */
    void remove(UUID actorId, UUID channelId, UUID userId);
}
