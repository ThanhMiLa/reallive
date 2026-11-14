package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.StreamSessionClosureService;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamSessionClosureServiceImpl implements StreamSessionClosureService {
    ChannelStreamConfigRepository configs;
    StreamSessionRepository sessions;
    MediaServerAdapter media;
    StreamSessionLifecycleSupport lifecycle;
    PlatformTransactionManager transactionManager;

    @Override
    public boolean expireReconnectIfDue(UUID sessionId) {
        var channelId = sessions.findChannelIdById(sessionId);
        if (channelId.isEmpty()) return false;
        return Boolean.TRUE.equals(
                new TransactionTemplate(transactionManager)
                        .execute(
                                status -> {
                                    var config = configs.findByIdForUpdate(channelId.get()).orElse(null);
                                    var session = sessions.findByIdForUpdate(sessionId).orElse(null);
                                    if (config == null
                                            || session == null
                                            || session.getStatus() != StreamSessionStatus.RECONNECTING
                                            || !lifecycle.isReconnectExpired(session, lifecycle.nowUtc())
                                    )
                                        return false;
                                    var source = media.getIngestSource(session.getIngestKeyId())
                                                    .orElse(null);
                                    if (source != null
                                            && source.online()
                                            && source.onlineTime() != null
                                            && lifecycle.toUtcLocalDateTime(source.onlineTime())
                                                    .isBefore(session.getReconnectDeadlineAt())
                                    )
                                        return false;
                                    lifecycle.closePlaybackPathAndVerify(sessionId);
                                    return lifecycle.finalizeEndedSession(
                                            config,
                                            session,
                                            lifecycle.nowUtc(),
                                            "Reconnect grace expired",
                                            null,
                                            null);
                                }));
    }
}
