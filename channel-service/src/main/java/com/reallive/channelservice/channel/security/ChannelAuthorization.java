package com.reallive.channelservice.channel.security;

import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.ChannelModeratorId;
import com.reallive.channelservice.channel.repository.ChannelModeratorRepository;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import com.reallive.channelservice.common.util.AuthenticationUserId;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component("channelAuthorization")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChannelAuthorization {

    ChannelRepository channels;
    ChannelModeratorRepository moderators;

    public boolean canModerate(Authentication authentication, UUID channelId) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        UUID actorId = AuthenticationUserId.getAuthenticatedUserId(jwt);
        var channel = channels.findById(channelId)
                .orElseThrow(() -> new AppException(ErrorCode.CHANNEL_NOT_FOUND));
        return isOwnerOrModerator(channel, actorId);
    }

    public boolean isOwnerOrModerator(ChannelEntity channel, UUID actorId) {
        if (channel.getUserId() == null) {
            throw new AppException(ErrorCode.CHANNEL_DATA_UNAVAILABLE);
        }
        return channel.getUserId().equals(actorId)
                || moderators.existsById(new ChannelModeratorId(channel.getId(), actorId));
    }
}
