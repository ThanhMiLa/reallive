package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.dto.request.LivestreamSummariesRequest;
import com.reallive.channelservice.channel.dto.response.LivestreamSummariesResponse;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.repository.CategoryRepository;
import com.reallive.channelservice.channel.service.LivestreamSummaryService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class LivestreamSummaryServiceImpl implements LivestreamSummaryService {
    ChannelRepository channels;
    CategoryRepository categories;

    @Override
    @Transactional(readOnly = true)
    public LivestreamSummariesResponse getSummaries(LivestreamSummariesRequest request) {
        var channelSummaries = channels.findAllById(request.channelIds()).stream()
                .map(c -> new LivestreamSummariesResponse.ChannelSummary(c.getId(), c.getName(), c.getSlug(), c.getAvatarUrl()))
                .toList();
        var categorySummaries = categories.findAllById(request.categoryIds()).stream()
                .map(c -> new LivestreamSummariesResponse.CategorySummary(c.getId(), c.getName(), c.getSlug()))
                .toList();
        return new LivestreamSummariesResponse(channelSummaries, categorySummaries);
    }
}
