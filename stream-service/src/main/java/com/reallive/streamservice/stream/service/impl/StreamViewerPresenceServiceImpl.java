package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.redis.StreamViewerPresenceStore;
import com.reallive.streamservice.stream.configuration.CcuProperties;
import com.reallive.streamservice.stream.dto.request.ViewerPresenceEvent;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.StreamViewerPresenceService;
import io.micrometer.core.instrument.Metrics;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamViewerPresenceServiceImpl implements StreamViewerPresenceService {
    CcuProperties properties;
    StreamViewerPresenceStore store;
    StreamSessionRepository sessions;
    Clock streamClock;

    @Override
    public void accept(ViewerPresenceEvent event) {
        if (!properties.isEnabled()) return;
        if (event.eventType() == ViewerPresenceEvent.Type.READ && event.userId() == null)
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        Metrics.counter("ccu.callback.received", "type", event.eventType().name()).increment();
        var now = streamClock.instant();
        Metrics.timer("ccu.callback.delay", "type", event.eventType().name())
                .record(Math.max(0, Duration.between(event.occurredAt(), now).toMillis()), TimeUnit.MILLISECONDS);
        if (event.occurredAt().isAfter(now.plusSeconds(5)) || event.occurredAt().isBefore(now.minusSeconds(60))) return;
        var session = sessions.findById(event.sessionId()).orElse(null);
        if (session == null || session.getStatus() == StreamSessionStatus.ENDED) return;
        if (event.eventType() == ViewerPresenceEvent.Type.UNREAD) {
            store.leave(event.sessionId(), event.readerId());
            return;
        }
        if (event.userId().equals(session.getOwnerUserId())) return;
        if (session.getStatus() == StreamSessionStatus.READY) throw new AppException(ErrorCode.STREAM_SESSION_STATE_INVALID);
        store.join(event.sessionId(), event.userId(), event.readerId());
    }

    @Override
    public Long count(UUID sessionId, StreamSessionStatus status) {
        if (status == StreamSessionStatus.READY || status == StreamSessionStatus.ENDED) return 0L;
        if (!properties.isEnabled()) return null;
        try {
            return store.count(sessionId);
        } catch (RuntimeException exception) {
            Metrics.counter("ccu.count.failures").increment();
            log.warn("CCU count unavailable: sessionId={}", sessionId);
            return null;
        }
    }

    @Override
    public void closeAfterCommit(UUID sessionId) {
        if (!properties.isEnabled()) return;
        Runnable cleanup = () -> {
            try { store.close(sessionId); }
            catch (RuntimeException exception) { log.warn("CCU cleanup pending: sessionId={}", sessionId); }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cleanup.run(); }
            });
        } else cleanup.run();
    }
}
