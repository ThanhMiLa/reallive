package com.reallive.streamservice.stream.dto.response;

import com.reallive.streamservice.client.channel.LivestreamSummaries;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StreamDiscoveryResponse(
        List<Item> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public record Item(
            UUID id,
            String title,
            String status,
            Instant startedAt,
            String thumbnailUrl,
            Instant thumbnailUpdatedAt,
            UUID channelId,
            UUID categoryId,
            LivestreamSummaries.ChannelSummary channel,
            LivestreamSummaries.CategorySummary category,
            Long currentViewerCount
    ) {}
}
