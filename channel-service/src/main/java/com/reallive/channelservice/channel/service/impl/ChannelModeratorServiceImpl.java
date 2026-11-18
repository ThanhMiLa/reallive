package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.client.user.UserStatusApiClient;
import com.reallive.channelservice.channel.client.user.UserStatusClient.Status;
import com.reallive.channelservice.channel.entity.ChannelModeratorEntity;
import com.reallive.channelservice.channel.entity.ChannelModeratorId;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelModeratorRepository;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.repository.ChannelUserBanRepository;
import com.reallive.channelservice.channel.service.ChannelModeratorService;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChannelModeratorServiceImpl implements ChannelModeratorService {

    ChannelRepository channels;
    ChannelModeratorRepository moderators;
    ChannelUserBanRepository bans;
    UserStatusApiClient users;

    @Override
    @Transactional
    public void assign(UUID actorId, UUID channelId, UUID userId) {
        requireOwnedActiveChannel(actorId, channelId);
        if (actorId.equals(userId)) {
            throw new AppException(ErrorCode.CANNOT_ASSIGN_CHANNEL_OWNER_AS_MODERATOR);
        }
        var assignment = new ChannelModeratorId(channelId, userId);
        if (moderators.existsById(assignment)) {
            throw new AppException(ErrorCode.CHANNEL_MODERATOR_ALREADY_ASSIGNED);
        }
        if (bans.existsByChannelIdAndUserId(channelId, userId)) {
            throw new AppException(ErrorCode.CANNOT_ASSIGN_BANNED_USER_AS_MODERATOR);
        }
        if (users.getStatus(userId) != Status.ACTIVE) {
            throw new AppException(ErrorCode.MODERATOR_TARGET_NOT_ACTIVE);
        }
        moderators.save(ChannelModeratorEntity.builder().id(assignment).build());
    }

    @Override
    @Transactional
    public void remove(UUID actorId, UUID channelId, UUID userId) {
        requireOwnedActiveChannel(actorId, channelId);
        var assignment = moderators.findById(new ChannelModeratorId(channelId, userId))
                .orElseThrow(() -> new AppException(ErrorCode.CHANNEL_MODERATOR_NOT_FOUND));
        moderators.delete(assignment);
    }

    private void requireOwnedActiveChannel(UUID actorId, UUID channelId) {
        var channel = channels.findByIdForUpdate(channelId)
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
}
