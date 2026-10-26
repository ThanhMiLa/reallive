package com.reallive.streamservice.stream.client.channel;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.client.ClientResponses;
import lombok.RequiredArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChannelClient {
    ChannelApiClient api;

    public StreamingChannelContext requireContext(UUID streamerId, UUID ownerUserId) {
        var result = ClientResponses.read(() -> api.getContext(streamerId), ErrorCode.STREAM_CHANNEL_NOT_ELIGIBLE, Set.of(403, 404));
        if (result.channelId() == null || !streamerId.equals(result.streamerId()) || result.ownerUserId() == null
                || result.status() == null || !Set.of("ACTIVE", "BANNED").contains(result.status())) {
            throw new AppException(ErrorCode.STREAM_DATA_UNAVAILABLE);
        }
        if (!ownerUserId.equals(result.ownerUserId()) || !"ACTIVE".equals(result.status())) {
            throw new AppException(ErrorCode.STREAM_CHANNEL_NOT_ELIGIBLE);
        }
        return result;
    }

    public void requireCategory(UUID categoryId) {
        boolean valid = ClientResponses.read(() -> api.validateCategories(new ChannelApiClient.CategoryIds(Set.of(categoryId))),
                ErrorCode.STREAM_CATEGORY_INVALID, Set.of(400, 404));
        if (!valid) {
            throw new AppException(ErrorCode.STREAM_CATEGORY_INVALID);
        }
    }
}
