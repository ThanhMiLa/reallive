package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.redis.StreamViewerPresenceStore;
import com.reallive.streamservice.stream.adapter.redis.ViewerScanPage;
import com.reallive.streamservice.stream.dto.request.ViewerPresenceEvent;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.StreamViewerPresenceService;
import io.micrometer.core.instrument.Metrics;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamViewerPresenceServiceImpl implements StreamViewerPresenceService {
    static final int MAX_EMPTY_SCAN_ROUNDS = 5;

    @NonFinal
    @Value("${stream.ccu.enabled:false}")
    boolean ccuEnabled;
    StreamViewerPresenceStore store;
    StreamSessionRepository sessions;
    Clock streamClock;

    @Override
    public void accept(ViewerPresenceEvent event) {
        if (!ccuEnabled) return;
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
        if (!ccuEnabled) return null;
        try {
            return store.count(sessionId);
        } catch (RuntimeException exception) {
            Metrics.counter("ccu.count.failures").increment();
            log.warn("CCU count unavailable: sessionId={}", sessionId);
            return null;
        }
    }

    @Override
    public ViewerScanPage scan(UUID sessionId, StreamSessionStatus status, String cursor, int count) {
        if (status == StreamSessionStatus.READY || status == StreamSessionStatus.ENDED) {
            return ViewerScanPage.closed();
        }
        if (!ccuEnabled) {
            throw new AppException(ErrorCode.VIEWER_PRESENCE_UNAVAILABLE);
        }
        String nextCursor = cursor;
        for (int round = 0; round < MAX_EMPTY_SCAN_ROUNDS; round++) {
            ViewerScanPage page = readScanPage(sessionId, nextCursor, count);
            if (page.status() == ViewerScanPage.Status.UNAVAILABLE) {
                Metrics.counter("ccu.viewers.failures").increment();
                log.warn("CCU viewer scan unavailable: sessionId={}", sessionId);
                throw new AppException(ErrorCode.VIEWER_PRESENCE_UNAVAILABLE);
            }
            if (page.status() == ViewerScanPage.Status.CLOSED
                    || !page.viewerIds().isEmpty()
                    || "0".equals(page.nextCursor())) {
                return page;
            }
            nextCursor = page.nextCursor();
        }
        return new ViewerScanPage(ViewerScanPage.Status.ACTIVE, nextCursor, List.of());
    }

    private ViewerScanPage readScanPage(UUID sessionId, String cursor, int count) {
        try {
            return store.scanViewers(sessionId, cursor, count);
        } catch (AppException exception) {
            if (exception.getErrorCode() == ErrorCode.VIEWER_PRESENCE_UNAVAILABLE) {
                Metrics.counter("ccu.viewers.failures").increment();
                log.warn("CCU viewer scan unavailable: sessionId={}", sessionId);
            }
            throw exception;
        } catch (RuntimeException exception) {
            Metrics.counter("ccu.viewers.failures").increment();
            log.warn("CCU viewer scan unavailable: sessionId={}", sessionId);
            throw new AppException(ErrorCode.VIEWER_PRESENCE_UNAVAILABLE);
        }
    }

    @Override
    public void closeAfterCommit(UUID sessionId) {
        if (!ccuEnabled) return;
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
