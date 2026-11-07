package com.reallive.channelservice.channel.dto.response;

import java.util.List;
import java.util.UUID;

public record LivestreamSummariesResponse(
        List<ChannelSummary> channels,
        List<CategorySummary> categories
) {
    public record ChannelSummary(
            UUID id,
            String name,
            String slug,
            String avatarUrl
    ) {}
    public record CategorySummary(
            UUID id,
            String name,
            String slug
    ) {}
}
