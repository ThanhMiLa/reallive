package com.reallive.streamservice.stream.scheduler;

import com.reallive.streamservice.stream.adapter.media.CcuMediaSessions;
import com.reallive.streamservice.stream.adapter.redis.CcuMaintenanceStore;
import com.reallive.streamservice.stream.adapter.redis.StreamViewerPresenceStore;
import com.reallive.streamservice.stream.dto.StreamPresenceSessionSnapshot;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@ConditionalOnProperty(prefix = "stream.ccu", name = "enabled", havingValue = "true")
public class CcuReconciliationScheduler {
    private static final int SESSION_BATCH_SIZE = 100;

    StreamSessionRepository sessions;
    StreamViewerPresenceStore store;
    CcuMediaSessions media;
    CcuMaintenanceStore maintenance;
    private static final AtomicLong LAST_SUCCESS = new AtomicLong();
    static {
        Metrics.gauge("ccu.reconcile.last.success.epoch", LAST_SUCCESS);
    }

    @Scheduled(fixedDelayString = "${stream.ccu.reconcile-delay:30000}", scheduler = "ccuTaskScheduler")
    public void reconcile() {
        Metrics.counter("ccu.reconcile.attempts").increment();
        String owner = UUID.randomUUID().toString();
        boolean acquired = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        try {
            acquired = maintenance.acquire(owner);
            if (!acquired) return;
            cleanupEnded(deadline);
            Map<UUID, StreamPresenceSessionSnapshot> active = activeSessions(deadline);
            Map<UUID, UUID> generations = new HashMap<>();
            for (UUID id : active.keySet()) {
                requireTime(deadline);
                generations.put(id, store.beginRecovery(id));
            }
            Set<UUID> observed = new HashSet<>();
            for (int page = 0; ; page++) {
                requireTime(deadline);
                var readers = media.page(page);
                for (var reader : readers) {
                    requireTime(deadline);
                    var sessionId = sessionId(reader.path());
                    var session = active.get(sessionId);
                    if (session == null || generations.get(sessionId) == null) continue;
                    var user = userId(reader.user());
                    if (user == null || user.equals(session.ownerUserId())) continue;
                    observed.add(reader.id());
                    store.observe(sessionId, user, reader.id(), generations.get(sessionId));
                }
                if (readers.size() < 100) break;
            }
            for (var entry : active.entrySet()) {
                requireTime(deadline);
                UUID id = entry.getKey();
                if (generations.get(id) == null) continue;
                for (UUID reader : store.readers(id).keySet()) {
                    requireTime(deadline);
                    if (!observed.contains(reader) && media.get(reader).isEmpty()) store.leave(id, reader);
                }
            }
            completeRecoveries(generations, deadline);
            LAST_SUCCESS.set(Instant.now().getEpochSecond());
            Metrics.counter("ccu.reconcile.success").increment();
        } catch (RuntimeException exception) {
            Metrics.counter("ccu.reconcile.failures").increment();
            log.warn("CCU reconciliation incomplete; presence remains unavailable until recovery succeeds");
        } finally {
            if (acquired) try { maintenance.release(owner); }
            catch (RuntimeException exception) { log.warn("CCU reconciliation lease release failed"); }
        }
    }

    private Map<UUID, StreamPresenceSessionSnapshot> activeSessions(long deadline) {
        Map<UUID, StreamPresenceSessionSnapshot> result = new HashMap<>();
        for (int page = 0; ; page++) {
            requireTime(deadline);
            var batch = sessions.findPresenceSessionsByStatusIn(
                    List.of(StreamSessionStatus.LIVE, StreamSessionStatus.RECONNECTING), PageRequest.of(page, SESSION_BATCH_SIZE));
            batch.forEach(session -> result.put(session.id(), session));
            if (!batch.hasNext()) return result;
        }
    }

    private void cleanupEnded(long deadline) {
        var tracked = new ArrayList<>(maintenance.trackedSessions(deadline));
        for (int offset = 0; offset < tracked.size(); offset += SESSION_BATCH_SIZE) {
            var batch = tracked.subList(offset, Math.min(offset + SESSION_BATCH_SIZE, tracked.size()));
            var statuses = currentStatuses(batch, deadline);
            for (UUID id : batch) {
                requireTime(deadline);
                if (!statuses.containsKey(id) || statuses.get(id) == StreamSessionStatus.ENDED) {
                    store.close(id);
                }
            }
        }
    }

    private void completeRecoveries(Map<UUID, UUID> generations, long deadline) {
        var candidates = generations.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(Map.Entry::getKey)
                .toList();
        for (int offset = 0; offset < candidates.size(); offset += SESSION_BATCH_SIZE) {
            var batch = candidates.subList(offset, Math.min(offset + SESSION_BATCH_SIZE, candidates.size()));
            var statuses = currentStatuses(batch, deadline);
            for (UUID id : batch) {
                requireTime(deadline);
                if (!statuses.containsKey(id) || statuses.get(id) == StreamSessionStatus.ENDED) {
                    store.close(id);
                } else {
                    store.completeRecovery(id, generations.get(id));
                }
            }
        }
    }

    private Map<UUID, StreamSessionStatus> currentStatuses(Collection<UUID> sessionIds, long deadline) {
        requireTime(deadline);
        var snapshots = sessions.findStatusSnapshotsByIdIn(sessionIds);
        requireTime(deadline);
        Map<UUID, StreamSessionStatus> statuses = new HashMap<>();
        snapshots.forEach(snapshot -> statuses.put(snapshot.id(), snapshot.status()));
        return statuses;
    }

    private void requireTime(long deadline) {
        if (System.nanoTime() >= deadline) throw new IllegalStateException("CCU reconciliation deadline exceeded");
    }

    private UUID sessionId(String path) {
        if (path == null || !path.startsWith("live/")) return null;
        return userId(path.substring(5));
    }

    private UUID userId(String user) {
        try { return UUID.fromString(user); }
        catch (IllegalArgumentException | NullPointerException ignored) { return null; }
    }
}
