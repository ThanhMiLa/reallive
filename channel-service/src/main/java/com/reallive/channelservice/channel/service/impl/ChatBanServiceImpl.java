package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.client.user.UserChatProfileApiClient;
import com.reallive.channelservice.channel.dto.response.ChatBanResponse;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.ChannelUserBanEntity;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.repository.ChannelUserBanRepository;
import com.reallive.channelservice.channel.service.ChatBanService;
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
import com.reallive.channelservice.channel.client.user.UserChatProfileClient.ChatProfile;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChatBanServiceImpl implements ChatBanService {
    ChannelRepository channels;
    ChannelUserBanRepository bans;
    UserChatProfileApiClient profiles;

    @Override
    @Transactional
    public ChatBanResponse.State ban(UUID actorId, UUID channelId, UUID userId) {
        ChannelEntity channel = channel(actorId, channelId, true);
        if (channel.getUserId().equals(userId)) throw new AppException(ErrorCode.CANNOT_BAN_CHANNEL_OWNER);
        var existing = bans.findByChannelIdAndUserId(channelId, userId);
        if (existing.isPresent()) throw new AppException("CHAT_BAN".equals(existing.get().getBanType())
                ? ErrorCode.CHANNEL_CHAT_ALREADY_BANNED : ErrorCode.CHANNEL_CHAT_BAN_BLOCKED_BY_HIGHER_BAN);
        profiles.profile(userId);
        bans.saveAndFlush(ChannelUserBanEntity.builder().id(UUID.randomUUID()).channelId(channelId).userId(userId)
                .banType("CHAT_BAN").performedBy(actorId).reason("Banned from channel chat")
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build());
        return new ChatBanResponse.State(channelId, userId, "CHAT_BAN");
    }

    @Override
    @Transactional
    public ChatBanResponse.State unban(UUID actorId, UUID channelId, UUID userId) {
        channel(actorId, channelId, true);
        var ban = bans.findByChannelIdAndUserId(channelId, userId)
                .orElseThrow(() -> new AppException(ErrorCode.CHANNEL_CHAT_NOT_BANNED));
        if (!"CHAT_BAN".equals(ban.getBanType())) throw new AppException(ErrorCode.CHANNEL_CHAT_UNBAN_BLOCKED_BY_HIGHER_BAN);
        bans.delete(ban);
        return new ChatBanResponse.State(channelId, userId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public ChatBanResponse.State state(UUID actorId, UUID channelId, UUID userId) {
        channel(actorId, channelId, false);
        return new ChatBanResponse.State(channelId, userId, bans.findByChannelIdAndUserId(channelId, userId)
                .map(ChannelUserBanEntity::getBanType).orElse(null));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ChatBanResponse.Item> list(UUID actorId, UUID channelId, int page, int size) {
        channel(actorId, channelId, false);
        if (page < 0 || size < 1 || size > 50) throw new AppException(ErrorCode.CHANNEL_REQUEST_INVALID);
        var result = bans.findByChannelId(channelId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        Map<UUID, ChatProfile> snapshots = new HashMap<>();
        try {
            var ids = result.getContent().stream().map(ChannelUserBanEntity::getUserId).toList();
            if (!ids.isEmpty()) profiles.profiles(ids).forEach(profile -> snapshots.put(profile.userId(), profile));
        } catch (AppException exception) {
            // Ban management remains available when profile data is unavailable.
        }
        var items = result.getContent().stream().map(ban -> {
            var profile = snapshots.get(ban.getUserId());
            return new ChatBanResponse.Item(ban.getUserId(), profile == null ? null : profile.displayName(),
                    profile == null ? null : profile.avatarUrl(), ban.getBanType(), ban.getCreatedAt().toInstant(ZoneOffset.UTC));
        }).toList();
        return PageResponse.<ChatBanResponse.Item>builder().content(items).page(page).size(size)
                .totalElements(result.getTotalElements()).totalPages(result.getTotalPages()).build();
    }

    @Override
    @Transactional(readOnly = true)
    public ChatBanResponse.Context context(UUID actorId, UUID channelId, boolean canBan, boolean canUnban, boolean canViewBans) {
        var channel = channels.findById(channelId).orElseThrow(() -> new AppException(ErrorCode.CHANNEL_NOT_FOUND));
        if (channel.getUserId() == null) throw new AppException(ErrorCode.CHANNEL_DATA_UNAVAILABLE);
        boolean eligible = channel.getUserId().equals(actorId) && channel.getStatus() == ChannelStatus.ACTIVE;
        return new ChatBanResponse.Context(channelId, channel.getUserId(), eligible && canBan, eligible && canUnban,
                eligible && canViewBans);
    }

    private ChannelEntity channel(UUID actorId, UUID channelId, boolean lock) {
        var channel = (lock ? channels.findByIdForUpdate(channelId) : channels.findById(channelId))
                .orElseThrow(() -> new AppException(ErrorCode.CHANNEL_NOT_FOUND));
        if (channel.getUserId() == null) throw new AppException(ErrorCode.CHANNEL_DATA_UNAVAILABLE);
        if (!channel.getUserId().equals(actorId)) throw new AppException(ErrorCode.ACCESS_DENIED);
        if (channel.getStatus() != ChannelStatus.ACTIVE) throw new AppException(ErrorCode.CHANNEL_NOT_ACTIVE);
        return channel;
    }
}
