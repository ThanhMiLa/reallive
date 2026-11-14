package com.reallive.streamservice.stream.scheduler;

import com.reallive.streamservice.stream.adapter.media.dto.CcuMediaReader;
import com.reallive.streamservice.stream.adapter.media.CcuMediaSessions;
import com.reallive.streamservice.stream.adapter.redis.CcuMaintenanceStore;
import com.reallive.streamservice.stream.adapter.redis.StreamViewerPresenceStore;
import com.reallive.streamservice.stream.dto.StreamPresenceSessionSnapshot;
import com.reallive.streamservice.stream.dto.StreamSessionStatusSnapshot;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.stream.IntStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CcuReconciliationSchedulerTest {
    final StreamSessionRepository sessions = mock(StreamSessionRepository.class);
    final StreamViewerPresenceStore store = mock(StreamViewerPresenceStore.class);
    final CcuMediaSessions media = mock(CcuMediaSessions.class);
    final CcuMaintenanceStore maintenance = mock(CcuMaintenanceStore.class);
    final CcuReconciliationScheduler scheduler = new CcuReconciliationScheduler(sessions, store, media, maintenance);
    final UUID sid = UUID.randomUUID(), owner = UUID.randomUUID(), viewer = UUID.randomUUID(), reader = UUID.randomUUID(), generation = UUID.randomUUID();
    final StreamSessionEntity session = new StreamSessionEntity();

    @BeforeEach void setup() {
        session.setId(sid); session.setOwnerUserId(owner); session.setStatus(StreamSessionStatus.LIVE);
        when(maintenance.acquire(anyString())).thenReturn(true);
        when(maintenance.trackedSessions(anyLong())).thenReturn(Set.of());
        when(sessions.findPresenceSessionsByStatusIn(anyList(), any())).thenReturn(
                new SliceImpl<>(List.of(new StreamPresenceSessionSnapshot(sid, owner))));
        when(sessions.findStatusSnapshotsByIdIn(anyCollection())).thenAnswer(call ->
                List.of(new StreamSessionStatusSnapshot(sid, session.getStatus())));
        when(store.beginRecovery(sid)).thenReturn(generation);
        when(store.readers(sid)).thenReturn(Map.of());
    }
    @Test void restoresViewerButExcludesOwnerAndWorker() {
        when(media.page(0)).thenReturn(List.of(
                new CcuMediaReader(reader, "live/" + sid, viewer.toString()),
                new CcuMediaReader(UUID.randomUUID(), "live/" + sid, owner.toString()),
                new CcuMediaReader(UUID.randomUUID(), "live/" + sid, "media-worker")));
        scheduler.reconcile();
        verify(store).observe(sid, viewer, reader, generation);
        verify(store, times(1)).observe(any(), any(), any(), any());
        verify(store).completeRecovery(sid, generation);
        verify(maintenance).release(anyString());
    }
    @Test void confirmsAbsenceBeforeDeletingAndDoesNotDeleteOnApiFailure() {
        when(media.page(0)).thenReturn(List.of());
        when(store.readers(sid)).thenReturn(Map.of(reader, viewer));
        when(media.get(reader)).thenThrow(new IllegalStateException("API offline"));
        scheduler.reconcile();
        verify(store, never()).leave(any(), any());
        verify(store, never()).completeRecovery(any(), any());
    }
    @Test void confirmed404DeletesReader() {
        when(media.page(0)).thenReturn(List.of());
        when(store.readers(sid)).thenReturn(Map.of(reader, viewer));
        when(media.get(reader)).thenReturn(Optional.empty());
        scheduler.reconcile();
        verify(store).leave(sid, reader);
        verify(store).completeRecovery(sid, generation);
    }
    @Test void cleanupClosesEndedSessionsAndNeverRepublishesActiveState() {
        when(media.page(0)).thenReturn(List.of());
        when(maintenance.trackedSessions(anyLong())).thenReturn(Set.of(sid));
        session.setStatus(StreamSessionStatus.ENDED);
        when(sessions.findPresenceSessionsByStatusIn(anyList(), any())).thenReturn(new SliceImpl<>(List.of()));
        scheduler.reconcile();
        verify(store).close(sid);
        verify(store, never()).completeRecovery(any(), any());
    }
    @Test void leaseContentionDoesNotAccessMediaOrReleaseOthersLease() {
        when(maintenance.acquire(anyString())).thenReturn(false);
        scheduler.reconcile();
        verifyNoInteractions(media, store);
        verify(maintenance, never()).release(anyString());
    }

    @Test
    void batchesCleanupAndFinalStatusChecksWithoutPerSessionDatabaseLookups() {
        List<UUID> ids = IntStream.range(0, 250).mapToObj(index -> UUID.randomUUID()).toList();
        Map<UUID, UUID> owners = new LinkedHashMap<>();
        ids.forEach(id -> owners.put(id, UUID.randomUUID()));
        List<List<UUID>> statusBatches = new ArrayList<>();
        when(maintenance.trackedSessions(anyLong())).thenReturn(Set.copyOf(ids));
        when(sessions.findPresenceSessionsByStatusIn(anyList(), any())).thenAnswer(call -> {
            Pageable page = call.getArgument(1);
            int from = (int) page.getOffset();
            int to = Math.min(from + page.getPageSize(), ids.size());
            var snapshots = ids.subList(from, to).stream()
                    .map(id -> new StreamPresenceSessionSnapshot(id, owners.get(id))).toList();
            return new SliceImpl<>(snapshots, page, to < ids.size());
        });
        when(sessions.findStatusSnapshotsByIdIn(anyCollection())).thenAnswer(call -> {
            Collection<UUID> batch = call.getArgument(0);
            statusBatches.add(List.copyOf(batch));
            return batch.stream().map(id -> new StreamSessionStatusSnapshot(id, StreamSessionStatus.LIVE)).toList();
        });
        when(store.beginRecovery(any())).thenReturn(generation);
        when(store.readers(any())).thenReturn(Map.of());
        when(media.page(0)).thenReturn(List.of());

        scheduler.reconcile();

        assertEquals(List.of(100, 100, 50, 100, 100, 50),
                statusBatches.stream().map(List::size).toList());
        assertEquals(Set.copyOf(ids),
                Set.copyOf(statusBatches.subList(0, 3).stream().flatMap(List::stream).toList()));
        assertEquals(Set.copyOf(ids),
                Set.copyOf(statusBatches.subList(3, 6).stream().flatMap(List::stream).toList()));
        verify(sessions, times(3)).findPresenceSessionsByStatusIn(anyList(), any());
        verify(sessions, times(6)).findStatusSnapshotsByIdIn(anyCollection());
        verify(sessions, never()).findById(any());
        verify(store, times(250)).completeRecovery(any(), eq(generation));
        verify(store, never()).close(any());
    }

    @Test
    void rechecksStatusesAfterMediaWorkAndClosesAStreamThatJustEnded() {
        when(media.page(0)).thenAnswer(call -> {
            session.setStatus(StreamSessionStatus.ENDED);
            return List.of();
        });

        scheduler.reconcile();

        verify(store).close(sid);
        verify(store, never()).completeRecovery(any(), any());
        verify(sessions, never()).findById(any());
    }

    @Test
    void missingFinalStatusClosesTheSessionRatherThanPublishingRecoveredPresence() {
        when(media.page(0)).thenReturn(List.of());
        when(sessions.findStatusSnapshotsByIdIn(anyCollection())).thenReturn(List.of());

        scheduler.reconcile();

        verify(store).close(sid);
        verify(store, never()).completeRecovery(any(), any());
    }

    @Test
    void cleanupClosesOnlyMissingOrEndedSessionsFromTheBatch() {
        UUID missing = UUID.randomUUID(), ready = UUID.randomUUID(), live = UUID.randomUUID();
        when(maintenance.trackedSessions(anyLong())).thenReturn(Set.of(sid, missing, ready, live));
        when(sessions.findStatusSnapshotsByIdIn(anyCollection())).thenReturn(List.of(
                new StreamSessionStatusSnapshot(sid, StreamSessionStatus.ENDED),
                new StreamSessionStatusSnapshot(ready, StreamSessionStatus.READY),
                new StreamSessionStatusSnapshot(live, StreamSessionStatus.LIVE)));
        when(sessions.findPresenceSessionsByStatusIn(anyList(), any())).thenReturn(new SliceImpl<>(List.of()));
        when(media.page(0)).thenReturn(List.of());

        scheduler.reconcile();

        verify(store).close(sid);
        verify(store).close(missing);
        verify(store, never()).close(ready);
        verify(store, never()).close(live);
        verify(sessions, times(1)).findStatusSnapshotsByIdIn(anyCollection());
        verify(sessions, never()).findById(any());
    }

    @Test
    void failedFinalStatusBatchDoesNotTreatEverySessionAsDeleted() {
        when(media.page(0)).thenReturn(List.of());
        when(sessions.findStatusSnapshotsByIdIn(anyCollection())).thenThrow(new IllegalStateException("Database unavailable"));

        scheduler.reconcile();

        verify(store, never()).close(any());
        verify(store, never()).completeRecovery(any(), any());
        verify(maintenance).release(anyString());
    }

    @Test
    void emptyListsDoNotIssueAnEmptyInQuery() {
        when(sessions.findPresenceSessionsByStatusIn(anyList(), any())).thenReturn(new SliceImpl<>(List.of()));
        when(media.page(0)).thenReturn(List.of());

        scheduler.reconcile();

        verify(sessions, never()).findStatusSnapshotsByIdIn(anyCollection());
    }

}
