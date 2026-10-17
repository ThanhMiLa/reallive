package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelFollowerRepository;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.service.ChannelFollowService;
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
public class ChannelFollowServiceImpl implements ChannelFollowService {

    ChannelRepository channelRepository;
    ChannelFollowerRepository channelFollowerRepository;

    @Override
    @Transactional
    public boolean followChannel(UUID userId, UUID channelId) {
        ChannelEntity channel = findChannel(userId, channelId);
        if (userId.equals(channel.getUserId())) {
            throw new AppException(ErrorCode.CANNOT_FOLLOW_OWN_CHANNEL);
        }
        if (channel.getStatus() != ChannelStatus.ACTIVE) {
            throw new AppException(ErrorCode.CHANNEL_NOT_ACTIVE);
        }
        if (channelFollowerRepository.insertFollowIfAbsent(channelId, userId) == 0) {
            throw new AppException(ErrorCode.CHANNEL_ALREADY_FOLLOWED);
        }
        if (channelRepository.incrementFollowerCount(channelId) != 1) {
            throw new AppException(ErrorCode.CHANNEL_DATA_UNAVAILABLE);
        }
        return true;
    }

    @Override
    @Transactional
    public boolean unfollowChannel(UUID userId, UUID channelId) {
        findChannel(userId, channelId);
        if (channelFollowerRepository.deleteFollow(channelId, userId) == 0) {
            throw new AppException(ErrorCode.CHANNEL_NOT_FOLLOWED);
        }
        if (channelRepository.decrementFollowerCount(channelId) != 1) {
            throw new AppException(ErrorCode.CHANNEL_DATA_UNAVAILABLE);
        }
        return true;
    }

    private ChannelEntity findChannel(UUID userId, UUID channelId) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (channelId == null) {
            throw new AppException(ErrorCode.CHANNEL_REQUEST_INVALID);
        }
        return channelRepository.findById(channelId)
                .orElseThrow(() -> new AppException(ErrorCode.CHANNEL_NOT_FOUND));
    }
}
