package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.client.user.UserChatProfileApiClient;
import com.reallive.channelservice.channel.client.user.UserChatProfileClient.ChatProfile;
import com.reallive.channelservice.channel.dto.response.ChannelBanResponse;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.ChannelUserBanEntity;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.entity.enums.ChannelBanType;
import com.reallive.channelservice.channel.entity.ChannelModeratorId;
import com.reallive.channelservice.channel.repository.ChannelModeratorRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Arrays;
import java.util.List;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.repository.ChannelUserBanRepository;
import com.reallive.channelservice.channel.service.ChannelBanService;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChannelBanServiceImpl implements ChannelBanService {
    ChannelRepository channels;
    ChannelUserBanRepository bans;
    UserChatProfileApiClient profiles;
    ChannelModeratorRepository moderators;

    @Override
    @Transactional
    public ChannelBanResponse.State banUserFromChannel(UUID actorId, UUID channelId, UUID userId, ChannelBanType requested) {
        ChannelEntity channel = channel(actorId, channelId, true);
        if (channel.getUserId().equals(userId)) throw new AppException(ErrorCode.CANNOT_BAN_CHANNEL_OWNER);
        requirePermission(requested.banPermission());
        var existing = bans.findByChannelIdAndUserId(channelId, userId);
        if (existing.isPresent()) {
            var ban = existing.get();
            if (ban.getBanType() == requested) throw new AppException(ErrorCode.CHANNEL_USER_ALREADY_BANNED);
            if (requested.ordinal() < ban.getBanType().ordinal()) requirePermission(ban.getBanType().unbanPermission());
            ban.setBanType(requested);
            ban.setPerformedBy(actorId);
            ban.setReason(requested.reason());
            bans.saveAndFlush(ban);
        } else {
            profiles.profile(userId);
            bans.saveAndFlush(ChannelUserBanEntity.builder().id(UUID.randomUUID()).channelId(channelId).userId(userId)
                    .banType(requested).performedBy(actorId).reason(requested.reason())
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build());
        }
        return state(channelId, userId, requested, false);
    }

    @Override
    @Transactional
    public ChannelBanResponse.State unbanUserFromChannel(UUID actorId, UUID channelId, UUID userId) {
        channel(actorId, channelId, true);
        var ban = bans.findByChannelIdAndUserId(channelId, userId)
                .orElseThrow(() -> new AppException(ErrorCode.CHANNEL_USER_NOT_BANNED));
        requirePermission(ban.getBanType().unbanPermission());
        bans.delete(ban);
        return state(channelId, userId, null, false);
    }

    @Override
    @Transactional(readOnly = true)
    public ChannelBanResponse.State getUserBanStatus(UUID actorId, UUID channelId, UUID userId) {
        var channel = channel(actorId, channelId, false);
        var type = bans.findByChannelIdAndUserId(channelId, userId).map(ChannelUserBanEntity::getBanType).orElse(null);
        return state(channelId, userId, type, channel.getUserId().equals(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ChannelBanResponse.Item> getBannedUsers(UUID actorId, UUID channelId, int page, int size) {
        var channel = channel(actorId, channelId, false);
        if (page < 0 || size < 1 || size > 50) throw new AppException(ErrorCode.CHANNEL_REQUEST_INVALID);
        var result = bans.findByChannelId(channelId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        Map<UUID, ChatProfile> snapshots = new HashMap<>();
        try {
            var ids = result.getContent().stream().map(ChannelUserBanEntity::getUserId).toList();
            if (!ids.isEmpty()) profiles.profiles(ids).forEach(profile -> snapshots.put(profile.userId(), profile));
        } catch (AppException exception) {
            // Profile lookup failure does not prevent ban management.
        }
        var items = result.getContent().stream().map(ban -> {
            var profile = snapshots.get(ban.getUserId());
            var state = state(channelId, ban.getUserId(), ban.getBanType(), channel.getUserId().equals(ban.getUserId()));
            return new ChannelBanResponse.Item(ban.getUserId(), profile == null ? null : profile.displayName(),
                    profile == null ? null : profile.avatarUrl(), ban.getBanType(), ban.getCreatedAt().toInstant(ZoneOffset.UTC),
                    state.allowedBanTypes(), state.canUnban());
        }).toList();
        return PageResponse.<ChannelBanResponse.Item>builder().content(items).page(page).size(size)
                .totalElements(result.getTotalElements()).totalPages(result.getTotalPages()).build();
    }

    @Override
    @Transactional(readOnly = true)
    public ChannelBanResponse.Context getChannelModerationContext(UUID actorId, UUID channelId) {
        var channel = channels.findById(channelId).orElseThrow(() -> new AppException(ErrorCode.CHANNEL_NOT_FOUND));
        if (channel.getUserId() == null) throw new AppException(ErrorCode.CHANNEL_DATA_UNAVAILABLE);
        boolean eligible = channel.getStatus() == ChannelStatus.ACTIVE && (channel.getUserId().equals(actorId)
                || moderators.existsById(new ChannelModeratorId(channelId, actorId)));
        return new ChannelBanResponse.Context(channelId, channel.getUserId(),
                eligible ? Arrays.stream(ChannelBanType.values()).filter(type -> hasPermission(type.banPermission())).toList() : List.of(),
                eligible ? Arrays.stream(ChannelBanType.values()).filter(type -> hasPermission(type.unbanPermission())).toList() : List.of(),
                eligible && hasPermission("CHANNEL_BAN_VIEW"));
    }

    private ChannelBanResponse.State state(UUID channelId, UUID userId, ChannelBanType current, boolean owner) {
        var allowed = owner ? List.<ChannelBanType>of() : Arrays.stream(ChannelBanType.values())
                .filter(type -> type != current && hasPermission(type.banPermission()))
                .filter(type -> current == null || type.ordinal() > current.ordinal() || hasPermission(current.unbanPermission()))
                .toList();
        return new ChannelBanResponse.State(channelId, userId, current, allowed,
                current != null && hasPermission(current.unbanPermission()));
    }

    private boolean hasPermission(String permission) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(authority -> permission.equals(authority.getAuthority()));
    }

    private void requirePermission(String permission) {
        if (!hasPermission(permission)) throw new AppException(ErrorCode.ACCESS_DENIED);
    }

    private ChannelEntity channel(UUID actorId, UUID channelId, boolean lock) {
        var channel = (lock ? channels.findByIdForUpdate(channelId) : channels.findById(channelId))
                .orElseThrow(() -> new AppException(ErrorCode.CHANNEL_NOT_FOUND));
        if (channel.getUserId() == null)
            throw new AppException(ErrorCode.CHANNEL_DATA_UNAVAILABLE);
        if (!channel.getUserId().equals(actorId) && !moderators.existsById(new ChannelModeratorId(channelId, actorId)))
            throw new AppException(ErrorCode.ACCESS_DENIED);
        if (channel.getStatus() != ChannelStatus.ACTIVE)
            throw new AppException(ErrorCode.CHANNEL_NOT_ACTIVE);
        return channel;
    }
}
