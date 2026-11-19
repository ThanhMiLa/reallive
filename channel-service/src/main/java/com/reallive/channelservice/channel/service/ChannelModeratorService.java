package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.response.ChannelModeratorResponse.Candidate;
import com.reallive.channelservice.channel.dto.response.ChannelModeratorResponse.Item;
import com.reallive.channelservice.common.dto.PageResponse;
import com.reallive.channelservice.common.exception.AppException;

import java.util.UUID;

public interface ChannelModeratorService {

    /**
     * Lists moderator assignments on an active channel owned by the caller.
     * Profile fields may be absent when account details are unavailable.
     *
     * @param actorId authenticated channel owner
     * @param channelId channel to list
     * @param page zero-based page number
     * @param size page size from 1 to 50
     * @return assignments ordered by assignment time descending, then user ID ascending
     * @throws AppException if the channel cannot be managed or pagination is invalid
     */
    PageResponse<Item> list(UUID actorId, UUID channelId, int page, int size);

    /**
     * Lists moderator assignments on an active channel for authenticated viewers.
     * Profile fields may be absent when account details are unavailable.
     *
     * @param channelId channel to list
     * @param page zero-based page number
     * @param size page size from 1 to 50
     * @return assignments ordered by assignment time descending, then user ID ascending
     * @throws AppException if the channel is missing or inactive, or pagination is invalid
     */
    PageResponse<Item> listForViewers(UUID channelId, int page, int size);

    /**
     * Searches the caller's active channel followers by display name and evaluates assignment eligibility.
     * Assignment revalidates eligibility when the caller submits a write.
     *
     * @param actorId authenticated channel owner
     * @param channelId channel that would receive the assignment
     * @param displayName case-insensitive display-name fragment
     * @param page zero-based page number
     * @param size page size from 1 to 50
     * @return a page of matching identities and their current assignment eligibility
     * @throws AppException if the channel cannot be managed, the search is invalid, or identity lookup fails
     */
    PageResponse<Candidate> searchCandidates(UUID actorId, UUID channelId, String displayName, int page, int size);

    /**
     * Assigns an active follower as moderator of an active channel owned by the caller.
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
