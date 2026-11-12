package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.dto.response.ChatAccessResponse;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.repository.ChannelUserBanRepository;
import com.reallive.channelservice.channel.service.ChatAccessService;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChatAccessServiceImpl implements ChatAccessService {
    ChannelRepository channels;
    ChannelUserBanRepository bans;

    @Override
    @Transactional(readOnly = true)
    public ChatAccessResponse resolve(UUID channelId, UUID userId) {
        var channel = channels.findById(channelId)
                .orElseThrow(() -> new AppException(ErrorCode.CHANNEL_NOT_FOUND));
        boolean active = channel.getStatus() == ChannelStatus.ACTIVE && channel.getDeletedAt() == null;
        var types = bans.findCurrent(channelId, userId).stream()
                .filter(event -> "BAN".equals(event.getAction()))
                .map(ChannelUserBanRepository.CurrentBan::getBanType)
                .collect(Collectors.toSet());
        boolean canView = active && !types.contains("FULL_BAN") && !types.contains("LIVE_VIEW_BAN");
        return new ChatAccessResponse(channelId, userId, canView, canView,
                canView && !types.contains("CHAT_BAN"));
    }
}
