package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.dto.request.CreateChannelRequest;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.service.ChannelService;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChannelServiceImpl implements ChannelService {

    ChannelRepository channelRepository;

    @Override
    @Transactional
    public boolean createChannel(CreateChannelRequest request) {
        if (channelRepository.findByStreamerId(request.getStreamerId()).isPresent()) {
            throw new AppException(ErrorCode.CHANNEL_ALREADY_EXISTED);
        }
        ChannelEntity channelEntity = ChannelEntity.builder()
                .name(request.getName())
                .streamerId(request.getStreamerId())
                .slug("channel-" + request.getStreamerId())
                .avatarUrl(request.getAvatarUrl() != null ? request.getAvatarUrl() : "")
                .status(ChannelStatus.ACTIVE)
                .build();
        try {
            ChannelEntity channel = channelRepository.saveAndFlush(channelEntity);
            return channel.getId() != null;
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.CHANNEL_DATA_UNAVAILABLE);
        }
    }
}

