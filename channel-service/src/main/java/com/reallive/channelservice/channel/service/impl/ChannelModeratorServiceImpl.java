package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.client.user.UserChatProfileApiClient;
import com.reallive.channelservice.channel.client.user.UserChatProfileClient.ChatProfile;
import com.reallive.channelservice.channel.client.user.UserStatusApiClient;
import com.reallive.channelservice.channel.client.user.UserStatusClient.Status;
import com.reallive.channelservice.channel.client.user.UserStatusClient.UserIdentity;
import com.reallive.channelservice.channel.dto.response.ChannelModeratorResponse.Candidate;
import com.reallive.channelservice.channel.dto.response.ChannelModeratorResponse.Eligibility;
import com.reallive.channelservice.channel.dto.response.ChannelModeratorResponse.Item;
import com.reallive.channelservice.channel.entity.ChannelFollowerId;
import com.reallive.channelservice.channel.entity.ChannelModeratorEntity;
import com.reallive.channelservice.channel.entity.ChannelModeratorId;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelFollowerRepository;
import com.reallive.channelservice.channel.repository.ChannelModeratorRepository;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.repository.ChannelUserBanRepository;
import com.reallive.channelservice.channel.service.ChannelModeratorService;
import com.reallive.channelservice.common.dto.PageResponse;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChannelModeratorServiceImpl implements ChannelModeratorService {

    ChannelRepository channels;
    ChannelFollowerRepository followers;
    ChannelModeratorRepository moderators;
    ChannelUserBanRepository bans;
    UserStatusApiClient users;
    UserChatProfileApiClient profiles;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<Item> list(UUID actorId, UUID channelId, int page, int size) {
        requireOwnedActiveChannel(actorId, channelId, false);
        return listAssignments(channelId, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<Item> listForViewers(UUID channelId, int page, int size) {
        var channel = channels.findById(channelId).orElseThrow(() -> new AppException(ErrorCode.CHANNEL_NOT_FOUND));
        if (channel.getStatus() != ChannelStatus.ACTIVE) {
            throw new AppException(ErrorCode.CHANNEL_NOT_ACTIVE);
        }
        return listAssignments(channelId, page, size);
    }

    private PageResponse<Item> listAssignments(UUID channelId, int page, int size) {
        validatePagination(page, size);
        var sort = Sort.by(Sort.Order.desc("assignedAt"), Sort.Order.asc("id.userId"));
        var assignments = moderators.findByIdChannelId(channelId, PageRequest.of(page, size, sort));
        var userIds = assignments.getContent().stream().map(assignment -> assignment.getId().getUserId()).toList();
        var snapshots = loadProfiles(userIds);
        var items = assignments.getContent().stream().map(assignment -> {
            UUID userId = assignment.getId().getUserId();
            var profile = snapshots.get(userId);
            return new Item(userId, profile == null ? null : profile.displayName(),
                    profile == null ? null : profile.avatarUrl(),
                    assignment.getAssignedAt().toInstant(ZoneOffset.UTC));
        }).toList();
        return PageResponse.<Item>builder().content(items).page(page).size(size)
                .totalElements(assignments.getTotalElements()).totalPages(assignments.getTotalPages()).build();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<Candidate> searchCandidates(UUID actorId, UUID channelId, String displayName, int page, int size) {
        requireOwnedActiveChannel(actorId, channelId, false);
        validatePagination(page, size);
        String fragment = displayName.strip();
        if (fragment.isEmpty() || fragment.length() > 100) {
            throw new AppException(ErrorCode.CHANNEL_REQUEST_INVALID);
        }

        var followerIds = followers.findFollowerUserIdsByChannelId(channelId);
        if (followerIds.isEmpty()) {
            return PageResponse.<Candidate>builder().content(List.of()).page(page).size(size)
                    .totalElements(0).totalPages(0).build();
        }

        var identities = users.searchIdentities(fragment, followerIds, page, size);
        var ids = identities.content().stream().map(UserIdentity::userId).toList();
        Set<UUID> assigned = ids.isEmpty() ? Set.of() : Set.copyOf(moderators.findUserIdsForChannel(channelId, ids));
        Set<UUID> banned = ids.isEmpty() ? Set.of() : Set.copyOf(bans.findUserIdsForChannel(channelId, ids));
        var candidates = identities.content().stream()
                .map(identity -> new Candidate(identity.userId(), identity.displayName(),
                        identity.avatarUrl(), candidateEligibility(actorId, identity, assigned, banned)))
                .toList();
        return PageResponse.<Candidate>builder().content(candidates).page(page).size(size)
                .totalElements(identities.totalElements()).totalPages(identities.totalPages()).build();
    }

    @Override
    @Transactional
    public void assign(UUID actorId, UUID channelId, UUID userId) {
        requireOwnedActiveChannel(actorId, channelId, true);
        var assignment = new ChannelModeratorId(channelId, userId);
        var rejection = switch (channelEligibility(actorId, assignment)) {
            case CHANNEL_OWNER -> ErrorCode.CANNOT_ASSIGN_CHANNEL_OWNER_AS_MODERATOR;
            case ALREADY_MODERATOR -> ErrorCode.CHANNEL_MODERATOR_ALREADY_ASSIGNED;
            case CHANNEL_BANNED -> ErrorCode.CANNOT_ASSIGN_BANNED_USER_AS_MODERATOR;
            case ACCOUNT_INACTIVE -> ErrorCode.MODERATOR_TARGET_NOT_ACTIVE;
            case ELIGIBLE -> null;
        };
        if (rejection != null) {
            throw new AppException(rejection);
        }
        if (users.getStatus(userId) != Status.ACTIVE) {
            throw new AppException(ErrorCode.MODERATOR_TARGET_NOT_ACTIVE);
        }
        if (!followers.existsById(new ChannelFollowerId(channelId, userId))) {
            throw new AppException(ErrorCode.MODERATOR_TARGET_NOT_FOLLOWING);
        }
        moderators.save(ChannelModeratorEntity.builder().id(assignment).build());
    }

    @Override
    @Transactional
    public void remove(UUID actorId, UUID channelId, UUID userId) {
        requireOwnedActiveChannel(actorId, channelId, true);
        var assignment = moderators.findById(new ChannelModeratorId(channelId, userId))
                .orElseThrow(() -> new AppException(ErrorCode.CHANNEL_MODERATOR_NOT_FOUND));
        moderators.delete(assignment);
    }

    private Eligibility channelEligibility(UUID ownerId, ChannelModeratorId assignment) {
        if (ownerId.equals(assignment.getUserId())) {
            return Eligibility.CHANNEL_OWNER;
        }
        if (moderators.existsById(assignment)) {
            return Eligibility.ALREADY_MODERATOR;
        }
        if (bans.existsByChannelIdAndUserId(assignment.getChannelId(), assignment.getUserId())) {
            return Eligibility.CHANNEL_BANNED;
        }
        return Eligibility.ELIGIBLE;
    }

    private Map<UUID, ChatProfile> loadProfiles(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        try {
            Map<UUID, ChatProfile> snapshots = new HashMap<>();
            profiles.profiles(userIds).forEach(profile -> snapshots.put(profile.userId(), profile));
            return snapshots;
        } catch (AppException exception) {
            return Map.of();
        }
    }

    private void requireOwnedActiveChannel(UUID actorId, UUID channelId, boolean lock) {
        var channel = (lock ? channels.findByIdForUpdate(channelId) : channels.findById(channelId))
                .orElseThrow(() -> new AppException(ErrorCode.CHANNEL_NOT_FOUND));
        if (channel.getUserId() == null) {
            throw new AppException(ErrorCode.CHANNEL_DATA_UNAVAILABLE);
        }
        if (!channel.getUserId().equals(actorId)) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        if (channel.getStatus() != ChannelStatus.ACTIVE) {
            throw new AppException(ErrorCode.CHANNEL_NOT_ACTIVE);
        }
    }

    private Eligibility candidateEligibility(UUID ownerId, UserIdentity identity, Set<UUID> assigned, Set<UUID> banned) {
        if (ownerId.equals(identity.userId())) {
            return Eligibility.CHANNEL_OWNER;
        }
        if (assigned.contains(identity.userId())) {
            return Eligibility.ALREADY_MODERATOR;
        }
        if (banned.contains(identity.userId())) {
            return Eligibility.CHANNEL_BANNED;
        }
        return identity.status() == Status.ACTIVE ? Eligibility.ELIGIBLE : Eligibility.ACCOUNT_INACTIVE;
    }

    private void validatePagination(int page, int size) {
        if (page < 0 || size < 1 || size > 50 || (long) page * size > Integer.MAX_VALUE) {
            throw new AppException(ErrorCode.CHANNEL_REQUEST_INVALID);
        }
    }
}
