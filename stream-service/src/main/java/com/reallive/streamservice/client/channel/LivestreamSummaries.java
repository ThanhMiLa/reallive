package com.reallive.streamservice.client.channel;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record LivestreamSummaries(
        List<ChannelSummary> channels,
        List<CategorySummary> categories
) {
    public record Request(
            Set<UUID> channelIds,
            Set<UUID> categoryIds
    ) {}
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
