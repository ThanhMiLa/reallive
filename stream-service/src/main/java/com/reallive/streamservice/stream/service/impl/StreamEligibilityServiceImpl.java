package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.client.channel.ChannelClient;
import com.reallive.streamservice.stream.client.user.UserClient;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import com.reallive.streamservice.stream.service.StreamEligibilityService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamEligibilityServiceImpl implements StreamEligibilityService {
    UserClient userClient;
    ChannelClient channelClient;

    @Override
    public StreamPublishContext requirePublishContext(UUID authenticatedUserId, UUID categoryId) {
        if (authenticatedUserId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (categoryId == null) {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }
        var user = userClient.requireEligibility(authenticatedUserId);
        var channel = channelClient.requireContext(user.streamerId(), authenticatedUserId);
        channelClient.requireCategory(categoryId);
        return new StreamPublishContext(authenticatedUserId, user.streamerId(), channel.channelId(), categoryId);
    }
}
