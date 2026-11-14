package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.stream.adapter.redis.StreamViewerPresenceStore;
import com.reallive.streamservice.stream.dto.request.ViewerPresenceEvent;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.impl.StreamViewerPresenceServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamViewerPresenceServiceTest {
    final Instant now = Instant.parse("2026-11-11T00:00:00Z");
    final UUID sid = UUID.randomUUID(), owner = UUID.randomUUID(), user = UUID.randomUUID(), reader = UUID.randomUUID();
    final StreamViewerPresenceStore store = mock(StreamViewerPresenceStore.class);
    final StreamSessionRepository sessions = mock(StreamSessionRepository.class);
    final StreamSessionEntity session = new StreamSessionEntity();
    final StreamViewerPresenceService service = new StreamViewerPresenceServiceImpl(store, sessions, Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "ccuEnabled", true);
        session.setId(sid); session.setOwnerUserId(owner); session.setStatus(StreamSessionStatus.LIVE);
        when(sessions.findById(sid)).thenReturn(Optional.of(session));
    }
    ViewerPresenceEvent event(ViewerPresenceEvent.Type type, UUID uid, Instant time) {
        return new ViewerPresenceEvent(UUID.randomUUID(), type, sid, reader, "hlsSession", uid, time);
    }
    @Test void recordsViewerButExcludesOwnerAndExpiredCallbacks() {
        service.accept(event(ViewerPresenceEvent.Type.READ, owner, now));
        service.accept(event(ViewerPresenceEvent.Type.READ, user, now.minusSeconds(61)));
        verifyNoInteractions(store);
        service.accept(event(ViewerPresenceEvent.Type.READ, user, now));
        verify(store).join(sid, user, reader);
    }
    @Test void unreadDoesNotRequireUserAndEndedNeverResurrects() {
        service.accept(event(ViewerPresenceEvent.Type.UNREAD, null, now));
        verify(store).leave(sid, reader);
        session.setStatus(StreamSessionStatus.ENDED);
        service.accept(event(ViewerPresenceEvent.Type.READ, user, now));
        verify(store, never()).join(any(), any(), any());
    }
    @Test void readyReadRetriesAndNullReadUserIsInvalid() {
        session.setStatus(StreamSessionStatus.READY);
        assertThrows(AppException.class, () -> service.accept(event(ViewerPresenceEvent.Type.READ, user, now)));
        assertThrows(AppException.class, () -> service.accept(event(ViewerPresenceEvent.Type.READ, null, now)));
    }
    @Test void unavailablePresenceDoesNotFailMetadataAndInactiveCountsAreZero() {
        when(store.count(sid)).thenThrow(new IllegalStateException("Redis offline"));
        assertNull(service.count(sid, StreamSessionStatus.LIVE));
        assertEquals(0L, service.count(sid, StreamSessionStatus.ENDED));
        assertEquals(0L, service.count(sid, StreamSessionStatus.READY));
    }
    @Test void disabledDoesNotAccessRedis() {
        ReflectionTestUtils.setField(service, "ccuEnabled", false);
        assertNull(service.count(sid, StreamSessionStatus.LIVE));
        service.accept(event(ViewerPresenceEvent.Type.READ, user, now));
        service.closeAfterCommit(sid);
        verifyNoInteractions(store);
    }
    @Test void cleanupWaitsForCommitAndFailureDoesNotEscape() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.closeAfterCommit(sid);
            verifyNoInteractions(store);
            doThrow(new IllegalStateException()).when(store).close(sid);
            assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit()));
            verify(store).close(sid);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void rollbackDoesNotPerformCleanup() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.closeAfterCommit(sid);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(1));
            verifyNoInteractions(store);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
}
