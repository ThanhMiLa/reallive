package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.stream.client.channel.ChannelClient;
import com.reallive.streamservice.stream.client.channel.LivestreamSummaries;
import com.reallive.streamservice.stream.dto.response.StreamDiscoveryResponse;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.StreamDiscoveryService;
import com.reallive.streamservice.stream.service.StreamViewerPresenceService;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamDiscoveryServiceImpl implements StreamDiscoveryService {
    StreamSessionRepository sessions;
    ChannelClient channelClient;
    PlatformTransactionManager transactionManager;
    StreamViewerPresenceService viewerPresence;

    @Override
    public StreamDiscoveryResponse list(int page, int size, UUID categoryId) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        Page<StreamDiscoveryResponse.Item> found = transaction.execute(status -> {
            var pageable = PageRequest.of(page, size);
            var result = categoryId == null ? sessions.findByStatus(StreamSessionStatus.LIVE, pageable)
                    : sessions.findByStatusAndCategoryId(StreamSessionStatus.LIVE, categoryId, pageable);
            return result.map(this::snapshot);
        });
        if (found.isEmpty()) {
            return new StreamDiscoveryResponse(List.of(), page, size, found.getTotalElements(), found.getTotalPages());
        }
        var channelIds = found.stream().map(StreamDiscoveryResponse.Item::channelId).collect(Collectors.toSet());
        var categoryIds = found.stream().map(StreamDiscoveryResponse.Item::categoryId).collect(Collectors.toSet());
        var summaries = channelClient.getLivestreamSummaries(channelIds, categoryIds);
        Map<UUID, LivestreamSummaries.ChannelSummary> channels = summaries.channels().stream()
                .collect(Collectors.toMap(LivestreamSummaries.ChannelSummary::id, Function.identity()));
        Map<UUID, LivestreamSummaries.CategorySummary> categories = summaries.categories().stream()
                .collect(Collectors.toMap(LivestreamSummaries.CategorySummary::id, Function.identity()));
        var content = found.stream().map(item -> new StreamDiscoveryResponse.Item(item.id(), item.title(), item.status(),
                item.startedAt(), item.thumbnailUrl(), item.thumbnailUpdatedAt(), item.channelId(), item.categoryId(),
                channels.get(item.channelId()), categories.get(item.categoryId()),
                viewerPresence.count(item.id(), StreamSessionStatus.LIVE))).toList();
        return new StreamDiscoveryResponse(content, page, size, found.getTotalElements(), found.getTotalPages());
    }

    private StreamDiscoveryResponse.Item snapshot(StreamSessionEntity session) {
        return new StreamDiscoveryResponse.Item(session.getId(), session.getTitle(), session.getStatus().name(),
                utc(session.getStartedAt()), session.getThumbnailUrl(), utc(session.getThumbnailUpdatedAt()),
                session.getChannelId(), session.getCategoryId(), null, null, null);
    }

    private Instant utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
