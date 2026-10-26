package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.dto.CreateSessionCommand;
import com.reallive.streamservice.stream.dto.MediaOfflineCommand;
import com.reallive.streamservice.stream.dto.MediaOnlineCommand;
import com.reallive.streamservice.stream.dto.SessionCreationResult;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.StreamStatusHistoryEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.repository.StreamStatusHistoryRepository;
import com.reallive.streamservice.stream.service.impl.StreamSessionDomainServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamStateMachineServiceTest {

    @Mock
    private StreamSessionRepository sessionRepository;

    @Mock
    private StreamStatusHistoryRepository historyRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Captor
    private ArgumentCaptor<StreamStatusHistoryEntity> historyCaptor;

    private MutableClock clock;
    private StreamSessionDomainServiceImpl domainService;

    private static final Instant BASE_INSTANT = Instant.parse("2026-10-25T12:00:00Z");

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BASE_INSTANT, ZoneOffset.UTC);
        domainService = new StreamSessionDomainServiceImpl(
                sessionRepository,
                historyRepository,
                transactionManager,
                clock
        );
        ReflectionTestUtils.setField(domainService, "reconnectGraceDuration", Duration.ofSeconds(60));
    }

    @Test
    void createReservationPersistsReadyOpenAndInitialHistory() {
        UUID channelId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        when(sessionRepository.findCreationForUpdate(ownerId, requestId)).thenReturn(Optional.empty());
        when(sessionRepository.findSlotOccupyingByChannelIdForUpdate(channelId)).thenReturn(Optional.empty());
        when(sessionRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CreateSessionCommand command = new CreateSessionCommand(
                channelId, streamerId, ownerId, categoryId, "My First Stream", requestId
        );

        SessionCreationResult result = domainService.createOrGetSession(command);

        assertTrue(result.created());
        StreamSessionEntity session = result.session();
        assertEquals(StreamSessionStatus.READY, session.getStatus());
        assertEquals(MediaPathState.OPEN, session.getMediaPathState());
        assertEquals(StreamVisibility.PUBLIC, session.getVisibility());
        assertEquals("My First Stream", session.getTitle());
        assertTrue(session.getPlaybackPath().startsWith("/live/"));

        verify(historyRepository).save(historyCaptor.capture());
        StreamStatusHistoryEntity history = historyCaptor.getValue();
        assertEquals(StreamSessionStatus.READY, history.getStatus());
        assertNull(history.getPreviousStatus());


    }

    @Test
    void createSessionRejectsWhenChannelSlotAlreadyOccupied() {
        UUID channelId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        when(sessionRepository.findCreationForUpdate(ownerId, requestId)).thenReturn(Optional.empty());
        when(sessionRepository.findSlotOccupyingByChannelIdForUpdate(channelId)).thenReturn(
                Optional.of(StreamSessionEntity.builder().channelId(channelId).status(StreamSessionStatus.READY).build())
        );

        CreateSessionCommand command = new CreateSessionCommand(
                channelId, UUID.randomUUID(), ownerId, UUID.randomUUID(), "Stream", requestId
        );

        AppException exception = assertThrows(AppException.class, () -> domainService.createOrGetSession(command));
        assertEquals(ErrorCode.STREAM_SESSION_ALREADY_ACTIVE, exception.getErrorCode());
    }

    @Test
    void readyToLiveSetsStartedAtAndMediaSourceInfo() {
        UUID streamId = UUID.randomUUID();
        StreamSessionEntity session = StreamSessionEntity.builder()
                .id(streamId)
                .status(StreamSessionStatus.READY)
                .mediaPathState(MediaPathState.OPEN)
                .build();

        when(sessionRepository.findByIdForUpdate(streamId)).thenReturn(Optional.of(session));
        when(sessionRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UUID sourceId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        MediaOnlineCommand command = new MediaOnlineCommand(
                streamId, sourceId, MediaSourceType.RTMP_CONN, eventId, null
        );

        StreamSessionEntity updated = domainService.onMediaOnline(command);

        assertEquals(StreamSessionStatus.LIVE, updated.getStatus());
        assertEquals(sourceId, updated.getMediaSourceId());
        assertEquals(MediaSourceType.RTMP_CONN, updated.getMediaSourceType());
        assertNotNull(updated.getStartedAt());
        assertNull(updated.getReconnectDeadlineAt());

        verify(historyRepository).save(historyCaptor.capture());
        StreamStatusHistoryEntity history = historyCaptor.getValue();
        assertEquals(StreamSessionStatus.READY, history.getPreviousStatus());
        assertEquals(StreamSessionStatus.LIVE, history.getStatus());
        assertEquals(eventId, history.getMediaEventId());
    }

    @Test
    void liveToReconnectingSetsSixtySecondDeadline() {
        UUID streamId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        StreamSessionEntity session = StreamSessionEntity.builder()
                .id(streamId)
                .status(StreamSessionStatus.LIVE)
                .mediaSourceId(sourceId)
                .mediaSourceType(MediaSourceType.RTMP_CONN)
                .startedAt(LocalDateTime.now(clock))
                .build();

        when(sessionRepository.findByIdForUpdate(streamId)).thenReturn(Optional.of(session));
        when(sessionRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MediaOfflineCommand command = new MediaOfflineCommand(
                streamId, sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null
        );

        StreamSessionEntity updated = domainService.onMediaOffline(command);

        assertEquals(StreamSessionStatus.RECONNECTING, updated.getStatus());
        assertNotNull(updated.getDisconnectedAt());
        assertEquals(updated.getDisconnectedAt().plusSeconds(60), updated.getReconnectDeadlineAt());

        verify(historyRepository).save(historyCaptor.capture());
        StreamStatusHistoryEntity history = historyCaptor.getValue();
        assertEquals(StreamSessionStatus.LIVE, history.getPreviousStatus());
        assertEquals(StreamSessionStatus.RECONNECTING, history.getStatus());
    }

    @Test
    void staleOfflineFromPreviousConnectionDoesNotDisruptActiveLiveStream() {
        UUID streamId = UUID.randomUUID();
        UUID activeSourceId = UUID.randomUUID();
        UUID oldSourceId = UUID.randomUUID();

        StreamSessionEntity session = StreamSessionEntity.builder()
                .id(streamId)
                .status(StreamSessionStatus.LIVE)
                .mediaSourceId(activeSourceId)
                .mediaSourceType(MediaSourceType.RTMP_CONN)
                .build();

        when(sessionRepository.findByIdForUpdate(streamId)).thenReturn(Optional.of(session));

        MediaOfflineCommand command = new MediaOfflineCommand(
                streamId, oldSourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null
        );

        StreamSessionEntity result = domainService.onMediaOffline(command);

        assertEquals(StreamSessionStatus.LIVE, result.getStatus());
        assertEquals(activeSourceId, result.getMediaSourceId());
        assertNull(result.getReconnectDeadlineAt());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateOfflinePreservesOriginalGraceDeadlineWithoutExtending() {
        UUID streamId = UUID.randomUUID();
        LocalDateTime originalDisconnect = LocalDateTime.now(clock);
        LocalDateTime originalDeadline = originalDisconnect.plusSeconds(60);

        StreamSessionEntity session = StreamSessionEntity.builder()
                .id(streamId)
                .status(StreamSessionStatus.RECONNECTING)
                .disconnectedAt(originalDisconnect)
                .reconnectDeadlineAt(originalDeadline)
                .build();

        when(sessionRepository.findByIdForUpdate(streamId)).thenReturn(Optional.of(session));

        // Advance clock by 30 seconds
        clock.advance(Duration.ofSeconds(30));

        MediaOfflineCommand command = new MediaOfflineCommand(
                streamId, UUID.randomUUID(), MediaSourceType.RTMP_CONN, UUID.randomUUID(), null
        );

        StreamSessionEntity result = domainService.onMediaOffline(command);

        assertEquals(StreamSessionStatus.RECONNECTING, result.getStatus());
        assertEquals(originalDeadline, result.getReconnectDeadlineAt());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void reconnectWithinGracePeriodRestoresLiveAndPreservesFirstStartedAt() {
        UUID streamId = UUID.randomUUID();
        LocalDateTime firstStartedAt = LocalDateTime.ofInstant(BASE_INSTANT.minusSeconds(120), ZoneOffset.UTC);
        LocalDateTime originalDisconnect = LocalDateTime.ofInstant(BASE_INSTANT, ZoneOffset.UTC);
        LocalDateTime originalDeadline = originalDisconnect.plusSeconds(60);

        StreamSessionEntity session = StreamSessionEntity.builder()
                .id(streamId)
                .status(StreamSessionStatus.RECONNECTING)
                .startedAt(firstStartedAt)
                .disconnectedAt(originalDisconnect)
                .reconnectDeadlineAt(originalDeadline)
                .build();

        when(sessionRepository.findByIdForUpdate(streamId)).thenReturn(Optional.of(session));
        when(sessionRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Reconnect 25 seconds later (within 60s grace)
        clock.advance(Duration.ofSeconds(25));
        UUID newSourceId = UUID.randomUUID();
        MediaOnlineCommand command = new MediaOnlineCommand(
                streamId, newSourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null
        );

        StreamSessionEntity updated = domainService.onMediaOnline(command);

        assertEquals(StreamSessionStatus.LIVE, updated.getStatus());
        assertEquals(newSourceId, updated.getMediaSourceId());
        assertEquals(firstStartedAt, updated.getStartedAt()); // Preserved!
        assertNull(updated.getDisconnectedAt());
        assertNull(updated.getReconnectDeadlineAt());

        verify(historyRepository).save(historyCaptor.capture());
        assertEquals(StreamSessionStatus.RECONNECTING, historyCaptor.getValue().getPreviousStatus());
        assertEquals(StreamSessionStatus.LIVE, historyCaptor.getValue().getStatus());
    }

    @Test
    void confirmedClosureExpiresReconnectAtTheExactDeadline() {
        UUID streamId = UUID.randomUUID();
        LocalDateTime disconnectTime = LocalDateTime.ofInstant(BASE_INSTANT, ZoneOffset.UTC);
        LocalDateTime deadline = disconnectTime.plusSeconds(60);

        StreamSessionEntity session = StreamSessionEntity.builder()
                .id(streamId)
                .status(StreamSessionStatus.RECONNECTING)
                .disconnectedAt(disconnectTime)
                .reconnectDeadlineAt(deadline)
                .build();

        when(sessionRepository.findByIdForUpdate(streamId)).thenReturn(Optional.of(session));
        when(sessionRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Advance 59 seconds: should not expire
        clock.advance(Duration.ofSeconds(59));
        Optional<StreamSessionEntity> notExpired = domainService.expireReconnectIfDue(streamId);
        assertTrue(notExpired.isEmpty());

        // The media closure precondition is satisfied by the caller before this domain mutation.
        clock.advance(Duration.ofSeconds(1));
        Optional<StreamSessionEntity> expired = domainService.expireReconnectIfDue(streamId);
        assertTrue(expired.isPresent());
        StreamSessionEntity endedSession = expired.get();
        assertEquals(StreamSessionStatus.ENDED, endedSession.getStatus());
        assertEquals(MediaPathState.CLOSED, endedSession.getMediaPathState());
        assertNotNull(endedSession.getEndedAt());
        assertNull(endedSession.getReconnectDeadlineAt());

        verify(historyRepository).save(historyCaptor.capture());
        assertEquals(StreamSessionStatus.ENDED, historyCaptor.getValue().getStatus());

    }

    @Test
    void ownerEndSessionIsIdempotentAndTerminal() {
        UUID streamId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        StreamSessionEntity session = StreamSessionEntity.builder()
                .id(streamId)
                .ownerUserId(ownerId)
                .status(StreamSessionStatus.LIVE)
                .mediaPathState(MediaPathState.OPEN)
                .startedAt(LocalDateTime.now(clock))
                .build();

        when(sessionRepository.findByIdForUpdate(streamId)).thenReturn(Optional.of(session));
        when(sessionRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        StreamSessionEntity ended = domainService.endSession(streamId, ownerId, "Stream finished normally");
        assertEquals(StreamSessionStatus.ENDED, ended.getStatus());
        assertEquals(MediaPathState.CLOSED, ended.getMediaPathState());
        assertNotNull(ended.getEndedAt());

        // Second call on already ENDED session returns cleanly without error
        StreamSessionEntity secondEnd = domainService.endSession(streamId, ownerId, "Repeat end");
        assertEquals(StreamSessionStatus.ENDED, secondEnd.getStatus());

        // Late online event does NOT reopen ENDED session
        MediaOnlineCommand lateOnline = new MediaOnlineCommand(
                streamId, UUID.randomUUID(), MediaSourceType.RTMP_CONN, UUID.randomUUID(), null
        );
        StreamSessionEntity lateOnlineResult = domainService.onMediaOnline(lateOnline);
        assertEquals(StreamSessionStatus.ENDED, lateOnlineResult.getStatus());
    }

    @Test
    void onlineAtOrAfterDeadlineNeverClosesOrRevivesTheSession() {
        LocalDateTime disconnect = LocalDateTime.now(clock);
        var session = StreamSessionEntity.builder()
                .status(StreamSessionStatus.RECONNECTING)
                .mediaPathState(MediaPathState.OPEN)
                .disconnectedAt(disconnect)
                .reconnectDeadlineAt(disconnect.plusSeconds(60))
                .build();
        when(sessionRepository.findByIdForUpdate(session.getId())).thenReturn(Optional.of(session));

        clock.advance(Duration.ofSeconds(60));
        domainService.onMediaOnline(new MediaOnlineCommand(session.getId(), UUID.randomUUID(),
                MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        clock.advance(Duration.ofSeconds(1));
        domainService.onMediaOnline(new MediaOnlineCommand(session.getId(), UUID.randomUUID(),
                MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));

        assertEquals(StreamSessionStatus.RECONNECTING, session.getStatus());
        assertEquals(MediaPathState.OPEN, session.getMediaPathState());
        assertNull(session.getEndedAt());
        verify(historyRepository, never()).save(any());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void onlineFromBeforeCurrentDisconnectIsIgnored() {
        LocalDateTime disconnect = LocalDateTime.now(clock);
        var session = StreamSessionEntity.builder()
                .status(StreamSessionStatus.RECONNECTING)
                .disconnectedAt(disconnect)
                .reconnectDeadlineAt(disconnect.plusSeconds(60))
                .build();
        when(sessionRepository.findByIdForUpdate(session.getId())).thenReturn(Optional.of(session));
        domainService.onMediaOnline(new MediaOnlineCommand(session.getId(), UUID.randomUUID(),
                MediaSourceType.RTMP_CONN, UUID.randomUUID(), disconnect.minusSeconds(1)));
        assertEquals(StreamSessionStatus.RECONNECTING, session.getStatus());
        verify(historyRepository, never()).save(any());
    }

    @Test
    void duplicateOnlineAndMediaEventDoNotAddHistoryOrResetStartedAt() {
        var source = UUID.randomUUID();
        LocalDateTime started = LocalDateTime.now(clock).minusMinutes(10);
        var session = StreamSessionEntity.builder().status(StreamSessionStatus.LIVE)
                .mediaSourceId(source).startedAt(started).build();
        when(sessionRepository.findByIdForUpdate(session.getId())).thenReturn(Optional.of(session));
        domainService.onMediaOnline(new MediaOnlineCommand(session.getId(), source,
                MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        var event = UUID.randomUUID();
        when(historyRepository.existsByMediaEventId(event)).thenReturn(true);
        domainService.onMediaOnline(new MediaOnlineCommand(session.getId(), UUID.randomUUID(),
                MediaSourceType.RTMP_CONN, event, null));
        assertEquals(started, session.getStartedAt());
        assertEquals(source, session.getMediaSourceId());
        verify(historyRepository, never()).save(any());
    }

    private static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        public MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        public void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
