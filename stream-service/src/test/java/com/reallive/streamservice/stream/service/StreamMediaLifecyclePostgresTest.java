package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.adapter.media.MediaPathConfiguration;
import com.reallive.streamservice.stream.adapter.media.MediaPathStatus;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.client.channel.ChannelClient;
import com.reallive.streamservice.stream.dto.MediaEventOutcome;
import com.reallive.streamservice.stream.dto.MediaOfflineCommand;
import com.reallive.streamservice.stream.dto.MediaOnlineCommand;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import com.reallive.streamservice.stream.dto.request.CreateStreamSessionRequest;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.repository.StreamStatusHistoryRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class StreamMediaLifecyclePostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final Instant BASE = Instant.parse("2026-10-27T08:00:00Z");

    @Autowired private StreamSessionService sessionService;
    @Autowired private StreamMediaLifecycleService lifecycleService;
    @Autowired private StreamSessionClosureService closureService;
    @Autowired private StreamSessionRepository sessions;
    @Autowired private StreamStatusHistoryRepository history;
    @Autowired private MutableClock clock;

    @MockitoBean private StreamEligibilityService eligibility;
    @MockitoBean private ChannelClient channels;
    @MockitoBean private MediaServerAdapter media;

    private final Set<UUID> configuredPaths = ConcurrentHashMap.newKeySet();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        configureDatabase(registry, SCHEMA);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        dropSchema(SCHEMA);
    }

    @BeforeEach
    void defaults() {
        history.deleteAll();
        sessions.deleteAll();
        clock.now = BASE;
        configuredPaths.clear();

        doAnswer(call -> {
            configuredPaths.add(call.getArgument(0));
            return null;
        }).when(media).ensurePath(any());

        when(media.getPath(any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            return configuredPaths.contains(id)
                    ? Optional.of(new MediaPathConfiguration("live/" + id, "publisher", false))
                    : Optional.empty();
        });

        doAnswer(call -> {
            configuredPaths.remove(call.getArgument(0));
            return null;
        }).when(media).deletePath(any());
    }

    @Test
    void onlineMatchingSourceTransitionsToLiveAndSetsStartedAtAndHistory() {
        var owner = createOwner();
        var session = createReadySession(owner);
        UUID sourceId = UUID.randomUUID();

        clock.now = BASE.plusSeconds(2);
        when(media.getSource(session.getId())).thenReturn(Optional.of(
                new MediaPathStatus("live/" + session.getId(), true, sourceId, MediaSourceType.RTMP_CONN)));

        var command = new MediaOnlineCommand(session.getId(), sourceId, MediaSourceType.RTMP_CONN,
                UUID.randomUUID(), clock.now.atZone(ZoneOffset.UTC).toLocalDateTime());

        var result = lifecycleService.onOnline(command);

        assertEquals(MediaEventOutcome.APPLIED, result.outcome());
        assertEquals(StreamSessionStatus.LIVE, result.session().getStatus());
        assertEquals(sourceId, result.session().getMediaSourceId());
        assertNotNull(result.session().getStartedAt());

        var reloaded = sessions.findById(session.getId()).orElseThrow();
        assertEquals(StreamSessionStatus.LIVE, reloaded.getStatus());

        var streamHistory = history.findByStreamIdOrderByCreatedAtAscIdAsc(session.getId());
        assertEquals(2, streamHistory.size()); // READY + LIVE
        assertEquals(StreamSessionStatus.READY, streamHistory.get(0).getStatus());
        assertEquals(StreamSessionStatus.LIVE, streamHistory.get(1).getStatus());
        assertEquals(StreamSessionStatus.READY, streamHistory.get(1).getPreviousStatus());
    }

    @Test
    void onlineMismatchingSourceOrOfflineIsIgnoredAndRemainsReady() {
        var owner = createOwner();
        var session = createReadySession(owner);
        UUID sourceId = UUID.randomUUID();

        // Source is not online according to MediaMTX
        when(media.getSource(session.getId())).thenReturn(Optional.of(
                new MediaPathStatus("live/" + session.getId(), false, sourceId, MediaSourceType.RTMP_CONN)));

        var command = new MediaOnlineCommand(session.getId(), sourceId, MediaSourceType.RTMP_CONN,
                UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime());

        var result = lifecycleService.onOnline(command);

        assertEquals(MediaEventOutcome.IGNORED, result.outcome());
        assertEquals(StreamSessionStatus.READY, result.session().getStatus());

        var reloaded = sessions.findById(session.getId()).orElseThrow();
        assertEquals(StreamSessionStatus.READY, reloaded.getStatus());
        assertEquals(1, history.findByStreamIdOrderByCreatedAtAscIdAsc(session.getId()).size());
    }

    @Test
    void duplicateOnlineEventIsIgnoredWithoutAdditionalHistory() {
        var owner = createOwner();
        var session = createReadySession(owner);
        UUID sourceId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        when(media.getSource(session.getId())).thenReturn(Optional.of(
                new MediaPathStatus("live/" + session.getId(), true, sourceId, MediaSourceType.RTMP_CONN)));

        var command = new MediaOnlineCommand(session.getId(), sourceId, MediaSourceType.RTMP_CONN,
                eventId, BASE.atZone(ZoneOffset.UTC).toLocalDateTime());

        var first = lifecycleService.onOnline(command);
        assertEquals(MediaEventOutcome.APPLIED, first.outcome());

        var second = lifecycleService.onOnline(command);
        assertEquals(MediaEventOutcome.IGNORED, second.outcome());

        assertEquals(2, history.findByStreamIdOrderByCreatedAtAscIdAsc(session.getId()).size());
    }

    @Test
    void offlineMatchingSourceTransitionsToReconnectingWith60sDeadline() {
        var owner = createOwner();
        var session = createReadySession(owner);
        UUID sourceId = UUID.randomUUID();

        when(media.getSource(session.getId())).thenReturn(Optional.of(
                new MediaPathStatus("live/" + session.getId(), true, sourceId, MediaSourceType.RTMP_CONN)));
        lifecycleService.onOnline(new MediaOnlineCommand(session.getId(), sourceId, MediaSourceType.RTMP_CONN,
                UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime()));

        // Now publisher disconnects, runtime path is offline
        when(media.getSource(session.getId())).thenReturn(Optional.of(
                new MediaPathStatus("live/" + session.getId(), false, null, null)));

        var offlineCmd = new MediaOfflineCommand(session.getId(), sourceId, MediaSourceType.RTMP_CONN,
                UUID.randomUUID(), BASE.plusSeconds(10).atZone(ZoneOffset.UTC).toLocalDateTime());

        clock.now = BASE.plusSeconds(10);
        var result = lifecycleService.onOffline(offlineCmd);

        assertEquals(MediaEventOutcome.APPLIED, result.outcome());
        assertEquals(StreamSessionStatus.RECONNECTING, result.session().getStatus());
        assertNotNull(result.session().getDisconnectedAt());
        assertEquals(BASE.plusSeconds(70).atZone(ZoneOffset.UTC).toLocalDateTime(),
                result.session().getReconnectDeadlineAt());

        var reloaded = sessions.findById(session.getId()).orElseThrow();
        assertEquals(StreamSessionStatus.RECONNECTING, reloaded.getStatus());
        assertEquals(3, history.findByStreamIdOrderByCreatedAtAscIdAsc(session.getId()).size());
    }

    @Test
    void offlineWhenSourceStillOnlineIsIgnored() {
        var owner = createOwner();
        var session = createReadySession(owner);
        UUID sourceId = UUID.randomUUID();

        when(media.getSource(session.getId())).thenReturn(Optional.of(
                new MediaPathStatus("live/" + session.getId(), true, sourceId, MediaSourceType.RTMP_CONN)));
        lifecycleService.onOnline(new MediaOnlineCommand(session.getId(), sourceId, MediaSourceType.RTMP_CONN,
                UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime()));

        // MediaMTX still reports an active connection online
        when(media.getSource(session.getId())).thenReturn(Optional.of(
                new MediaPathStatus("live/" + session.getId(), true, UUID.randomUUID(), MediaSourceType.RTMP_CONN)));

        var result = lifecycleService.onOffline(new MediaOfflineCommand(session.getId(), sourceId,
                MediaSourceType.RTMP_CONN, UUID.randomUUID(), BASE.plusSeconds(5).atZone(ZoneOffset.UTC).toLocalDateTime()));

        assertEquals(MediaEventOutcome.IGNORED, result.outcome());
        var reloaded = sessions.findById(session.getId()).orElseThrow();
        assertEquals(StreamSessionStatus.LIVE, reloaded.getStatus());
    }

    @Test
    void reconnectWithinGracePeriodRestoresLiveAndPreservesFirstStartedAt() {
        var owner = createOwner();
        var session = createReadySession(owner);
        UUID firstSource = UUID.randomUUID();

        when(media.getSource(session.getId())).thenReturn(Optional.of(
                new MediaPathStatus("live/" + session.getId(), true, firstSource, MediaSourceType.RTMP_CONN)));
        var online1 = lifecycleService.onOnline(new MediaOnlineCommand(session.getId(), firstSource,
                MediaSourceType.RTMP_CONN, UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime()));
        var firstStartedAt = online1.session().getStartedAt();

        // Disconnect at +10s
        clock.now = BASE.plusSeconds(10);
        when(media.getSource(session.getId())).thenReturn(Optional.empty());
        lifecycleService.onOffline(new MediaOfflineCommand(session.getId(), firstSource,
                MediaSourceType.RTMP_CONN, UUID.randomUUID(), clock.now.atZone(ZoneOffset.UTC).toLocalDateTime()));

        // Reconnect at +40s (within 60s grace)
        clock.now = BASE.plusSeconds(40);
        UUID secondSource = UUID.randomUUID();
        when(media.getSource(session.getId())).thenReturn(Optional.of(
                new MediaPathStatus("live/" + session.getId(), true, secondSource, MediaSourceType.RTMP_CONN)));

        var result = lifecycleService.onOnline(new MediaOnlineCommand(session.getId(), secondSource,
                MediaSourceType.RTMP_CONN, UUID.randomUUID(), clock.now.atZone(ZoneOffset.UTC).toLocalDateTime()));

        assertEquals(MediaEventOutcome.APPLIED, result.outcome());
        assertEquals(StreamSessionStatus.LIVE, result.session().getStatus());
        assertEquals(firstStartedAt, result.session().getStartedAt(), "startedAt must be preserved");
        assertEquals(secondSource, result.session().getMediaSourceId());
        assertNull(result.session().getReconnectDeadlineAt());
        assertNull(result.session().getDisconnectedAt());
    }

    @Test
    void expireReconnectBeforeDeadlineIsNoOp() {
        var owner = createOwner();
        var session = createReadySession(owner);
        UUID sourceId = UUID.randomUUID();

        when(media.getSource(session.getId())).thenReturn(Optional.of(
                new MediaPathStatus("live/" + session.getId(), true, sourceId, MediaSourceType.RTMP_CONN)));
        lifecycleService.onOnline(new MediaOnlineCommand(session.getId(), sourceId, MediaSourceType.RTMP_CONN,
                UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime()));

        when(media.getSource(session.getId())).thenReturn(Optional.empty());
        lifecycleService.onOffline(new MediaOfflineCommand(session.getId(), sourceId, MediaSourceType.RTMP_CONN,
                UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime()));

        // Advance 30s (deadline is at +60s)
        clock.now = BASE.plusSeconds(30);

        boolean expired = closureService.expireReconnectIfDue(session.getId());
        assertFalse(expired);

        verify(media, never()).deletePath(session.getId());
        var reloaded = sessions.findById(session.getId()).orElseThrow();
        assertEquals(StreamSessionStatus.RECONNECTING, reloaded.getStatus());
    }

    @Test
    void expireReconnectAtOrAfterDeadlineClosesMediaAndCommitsEnded() {
        var owner = createOwner();
        var session = createReadySession(owner);
        UUID sourceId = UUID.randomUUID();

        when(media.getSource(session.getId())).thenReturn(Optional.of(
                new MediaPathStatus("live/" + session.getId(), true, sourceId, MediaSourceType.RTMP_CONN)));
        lifecycleService.onOnline(new MediaOnlineCommand(session.getId(), sourceId, MediaSourceType.RTMP_CONN,
                UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime()));

        when(media.getSource(session.getId())).thenReturn(Optional.empty());
        lifecycleService.onOffline(new MediaOfflineCommand(session.getId(), sourceId, MediaSourceType.RTMP_CONN,
                UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime()));

        // Advance 60s (exact deadline)
        clock.now = BASE.plusSeconds(60);

        boolean expired = closureService.expireReconnectIfDue(session.getId());
        assertTrue(expired);

        verify(media).deletePath(session.getId());
        var reloaded = sessions.findById(session.getId()).orElseThrow();
        assertEquals(StreamSessionStatus.ENDED, reloaded.getStatus());
        assertEquals(MediaPathState.CLOSED, reloaded.getMediaPathState());
        assertNotNull(reloaded.getEndedAt());
        assertNull(reloaded.getReconnectDeadlineAt());
    }

    @Test
    void expireReconnectRollsBackWhenMediaFails() {
        var owner = createOwner();
        var session = createReadySession(owner);
        UUID sourceId = UUID.randomUUID();

        when(media.getSource(session.getId())).thenReturn(Optional.of(
                new MediaPathStatus("live/" + session.getId(), true, sourceId, MediaSourceType.RTMP_CONN)));
        lifecycleService.onOnline(new MediaOnlineCommand(session.getId(), sourceId, MediaSourceType.RTMP_CONN,
                UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime()));

        when(media.getSource(session.getId())).thenReturn(Optional.empty());
        lifecycleService.onOffline(new MediaOfflineCommand(session.getId(), sourceId, MediaSourceType.RTMP_CONN,
                UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime()));

        clock.now = BASE.plusSeconds(60);

        doThrow(new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE)).when(media).deletePath(session.getId());

        assertThrows(AppException.class, () -> closureService.expireReconnectIfDue(session.getId()));

        var reloaded = sessions.findById(session.getId()).orElseThrow();
        assertEquals(StreamSessionStatus.RECONNECTING, reloaded.getStatus());
        assertEquals(MediaPathState.OPEN, reloaded.getMediaPathState());
    }

    @Test
    void ownerEndSessionIsIdempotentAndClosesMedia() {
        var owner = createOwner();
        var session = createReadySession(owner);
        UUID sourceId = UUID.randomUUID();

        var sourceOnline = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(media.getSource(session.getId())).thenAnswer(call -> Optional.of(
                new MediaPathStatus("live/" + session.getId(), sourceOnline.get(), sourceId, MediaSourceType.RTMP_CONN)));
        doAnswer(call -> {
            sourceOnline.set(false);
            return null;
        }).when(media).kick(any(), any());

        lifecycleService.onOnline(new MediaOnlineCommand(session.getId(), sourceId, MediaSourceType.RTMP_CONN,
                UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime()));

        var response = closureService.end(session.getId(), owner.user(), "Manual end");
        assertEquals(StreamSessionStatus.ENDED, response.status());

        verify(media, times(1)).deletePath(session.getId());
        verify(media, times(1)).kick(MediaSourceType.RTMP_CONN, sourceId);
        var reloaded = sessions.findById(session.getId()).orElseThrow();
        assertEquals(StreamSessionStatus.ENDED, reloaded.getStatus());
        assertEquals(MediaPathState.CLOSED, reloaded.getMediaPathState());

        // Second End is idempotent no-op
        var second = closureService.end(session.getId(), owner.user(), "Ignored reason");
        assertEquals(StreamSessionStatus.ENDED, second.status());
        verify(media, times(1)).deletePath(session.getId());
    }

    @Test
    void endedSessionIgnoresSubsequentOnlineAndOfflineCallbacks() {
        var owner = createOwner();
        var session = createReadySession(owner);
        closureService.end(session.getId(), owner.user(), "Ended");

        reset(media);

        var online = lifecycleService.onOnline(new MediaOnlineCommand(session.getId(), UUID.randomUUID(),
                MediaSourceType.RTMP_CONN, UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime()));
        assertEquals(MediaEventOutcome.IGNORED, online.outcome());

        var offline = lifecycleService.onOffline(new MediaOfflineCommand(session.getId(), UUID.randomUUID(),
                MediaSourceType.RTMP_CONN, UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime()));
        assertEquals(MediaEventOutcome.IGNORED, offline.outcome());

        verifyNoInteractions(media);
    }

    @Test
    void concurrencyEndVsOfflineLeavesSessionInEndedState() throws Exception {
        var owner = createOwner();
        var session = createReadySession(owner);
        UUID sourceId = UUID.randomUUID();

        when(media.getSource(session.getId())).thenReturn(Optional.of(
                new MediaPathStatus("live/" + session.getId(), true, sourceId, MediaSourceType.RTMP_CONN)));
        lifecycleService.onOnline(new MediaOnlineCommand(session.getId(), sourceId, MediaSourceType.RTMP_CONN,
                UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime()));

        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        executor.submit(() -> {
            ready.countDown();
            try { start.await(); } catch (InterruptedException ignored) {}
            closureService.end(session.getId(), owner.user(), "End concurrent");
        });

        executor.submit(() -> {
            ready.countDown();
            try { start.await(); } catch (InterruptedException ignored) {}
            when(media.getSource(session.getId())).thenReturn(Optional.empty());
            lifecycleService.onOffline(new MediaOfflineCommand(session.getId(), sourceId,
                    MediaSourceType.RTMP_CONN, UUID.randomUUID(), BASE.atZone(ZoneOffset.UTC).toLocalDateTime()));
        });

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        var reloaded = sessions.findById(session.getId()).orElseThrow();
        assertEquals(StreamSessionStatus.ENDED, reloaded.getStatus());
        assertEquals(MediaPathState.CLOSED, reloaded.getMediaPathState());
    }

    private Owner createOwner() {
        var owner = new Owner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(eligibility.requirePublishContext(eq(owner.user()), any())).thenAnswer(call ->
                new StreamPublishContext(owner.user(), owner.streamer(), owner.channel(), call.getArgument(1)));
        return owner;
    }

    private StreamSessionEntity createReadySession(Owner owner) {
        var request = new CreateStreamSessionRequest(UUID.randomUUID(), "Session", owner.category());
        var res = sessionService.create(owner.user(), request);
        return sessions.findById(res.response().session().id()).orElseThrow();
    }

    private record Owner(
            UUID user,
            UUID streamer,
            UUID channel,
            UUID category
    ) {}

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        volatile Instant now = BASE;

        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) { return this; }

        @Override
        public Instant instant() { return now; }
    }
}
