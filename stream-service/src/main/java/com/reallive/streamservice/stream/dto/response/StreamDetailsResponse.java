package com.reallive.streamservice.stream.dto.response;

import java.util.UUID;

public record StreamDetailsResponse(
        UUID id,
        UUID channelId,
        UUID categoryId,
        ChannelSummary channel,
        CategorySummary category
) {
    public record ChannelSummary(
            UUID id,
            String name,
            String avatarUrl
    ) {}

    public record CategorySummary(
            UUID id,
            String name
    ) {}
}
