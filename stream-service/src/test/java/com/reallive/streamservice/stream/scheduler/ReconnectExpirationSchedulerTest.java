package com.reallive.streamservice.stream.scheduler;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.StreamSessionClosureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconnectExpirationSchedulerTest {

    @Mock private StreamSessionRepository sessions;
    @Mock private StreamSessionClosureService closure;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC);

    private ReconnectExpirationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ReconnectExpirationScheduler(sessions, closure, clock);
    }

    @Test
    void sweepsExpiredSessionsAndCallsClosure() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(sessions.findExpiredReconnectingIds(any(), eq(PageRequest.of(0, 20))))
                .thenReturn(List.of(id1, id2));

        scheduler.sweepExpiredReconnections();

        verify(closure).expireReconnectIfDue(id1);
        verify(closure).expireReconnectIfDue(id2);
    }

    @Test
    void stopsSweepOnMediaServerUnavailable() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(sessions.findExpiredReconnectingIds(any(), eq(PageRequest.of(0, 20))))
                .thenReturn(List.of(id1, id2));
        doThrow(new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE))
                .when(closure).expireReconnectIfDue(id1);

        scheduler.sweepExpiredReconnections();

        verify(closure).expireReconnectIfDue(id1);
        verify(closure, never()).expireReconnectIfDue(id2);
    }

    @Test
    void continuesSweepOnGenericError() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(sessions.findExpiredReconnectingIds(any(), eq(PageRequest.of(0, 20))))
                .thenReturn(List.of(id1, id2));
        doThrow(new RuntimeException("Transient DB issue"))
                .when(closure).expireReconnectIfDue(id1);

        scheduler.sweepExpiredReconnections();

        verify(closure).expireReconnectIfDue(id1);
        verify(closure).expireReconnectIfDue(id2);
    }

    @Test
    void doesNothingWhenNoExpiredSessions() {
        when(sessions.findExpiredReconnectingIds(any(), eq(PageRequest.of(0, 20))))
                .thenReturn(List.of());

        scheduler.sweepExpiredReconnections();

        verifyNoInteractions(closure);
    }
}
