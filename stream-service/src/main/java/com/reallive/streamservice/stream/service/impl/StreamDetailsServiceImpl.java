package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.client.channel.ChannelClient;
import com.reallive.streamservice.stream.dto.response.StreamDetailsResponse;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.StreamDetailsService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Transactional(propagation = Propagation.NEVER)
public class StreamDetailsServiceImpl implements StreamDetailsService {

    StreamSessionRepository sessions;
    ChannelClient channelClient;
    PlatformTransactionManager transactionManager;

    @Override
    public StreamDetailsResponse details(UUID sessionId) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        var snapshot = transaction.execute(status -> {
            var session = sessions.findById(sessionId)
                    .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND));
            requireViewableVisibility(session);
            return new SessionSnapshot(session.getId(), session.getChannelId(), session.getCategoryId());
        });
        var summaries = channelClient.getLivestreamSummaries(
                Set.of(snapshot.channelId()), Set.of(snapshot.categoryId()));
        var channel = summaries.channels().stream()
                .filter(item -> snapshot.channelId().equals(item.id()))
                .findFirst()
                .map(item -> new StreamDetailsResponse.ChannelSummary(item.id(), item.name(), item.avatarUrl()))
                .orElse(null);
        var category = summaries.categories().stream()
                .filter(item -> snapshot.categoryId().equals(item.id()))
                .findFirst()
                .map(item -> new StreamDetailsResponse.CategorySummary(item.id(), item.name()))
                .orElse(null);
        return new StreamDetailsResponse(
                snapshot.id(), snapshot.channelId(), snapshot.categoryId(), channel, category);
    }

    private void requireViewableVisibility(StreamSessionEntity session) {
        if (session.getVisibility() != StreamVisibility.PUBLIC) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
    }

    private record SessionSnapshot(
            UUID id,
            UUID channelId,
            UUID categoryId
    ) {}
}
