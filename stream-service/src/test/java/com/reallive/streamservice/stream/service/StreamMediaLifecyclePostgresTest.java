package com.reallive.streamservice.stream.service;

import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.adapter.media.MediaPathConfiguration;
import com.reallive.streamservice.stream.adapter.media.MediaPathStatus;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.dto.MediaEventOutcome;
import com.reallive.streamservice.stream.dto.MediaIngestEventCommand;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import com.reallive.streamservice.stream.dto.request.SaveStreamSetupRequest;
import com.reallive.streamservice.stream.dto.request.ThumbnailResultRequest;
import com.reallive.streamservice.stream.dto.response.MediaEventResponse;
import com.reallive.streamservice.stream.dto.response.ThumbnailResultOutcome;
import com.reallive.streamservice.stream.entity.StreamMediaOperationEntity;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.ChannelStreamConfigStatus;
import com.reallive.streamservice.stream.entity.enums.MediaOperationStatus;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.ThumbnailJobStatus;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
import com.reallive.streamservice.stream.repository.ChannelStreamCredentialRepository;
import com.reallive.streamservice.stream.repository.StreamMediaOperationRepository;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.repository.StreamStatusHistoryRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "stream.thumbnail.public-base-url=https://thumbnails.example.com")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class StreamMediaLifecyclePostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final Instant BASE = Instant.parse("2026-10-27T08:00:00Z");

    @Autowired private StreamSetupService setupService;
    @Autowired private StreamSessionService sessionService;
    @Autowired private StreamMediaLifecycleService lifecycleService;
    @Autowired private StreamMediaOperationWorker operationWorker;
    @Autowired private StreamSessionClosureService closureService;
    @Autowired private ThumbnailResultService thumbnailResultService;
    @Autowired private ChannelStreamConfigRepository configs;
    @Autowired private ChannelStreamCredentialRepository credentials;
    @Autowired private StreamMediaOperationRepository operations;
    @Autowired private StreamSessionRepository sessions;
    @Autowired private StreamStatusHistoryRepository history;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MutableClock clock;

    @MockitoBean private StreamEligibilityService eligibility;
    @MockitoBean private MediaServerAdapter media;

    private final Set<UUID> activeIngests = ConcurrentHashMap.newKeySet();
    private final Set<UUID> activePlaybacks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, MediaPathStatus> activeIngestSources = new ConcurrentHashMap<>();

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
        operations.deleteAll();
        sessions.deleteAll();
        credentials.deleteAll();
        configs.deleteAll();
        clock.now = BASE;
        activeIngests.clear();
        activePlaybacks.clear();
        activeIngestSources.clear();
        installDefaultMediaStubs();
    }

    private void installDefaultMediaStubs() {
        doAnswer(call -> {
            activeIngests.add(call.getArgument(0));
            return null;
        }).when(media).ensureIngestPath(any());
        doAnswer(call -> {
            UUID id = call.getArgument(0);
            activeIngests.remove(id);
            activeIngestSources.remove(id);
            return null;
        }).when(media).deleteIngestPath(any());
        doAnswer(call -> {
            UUID sourceId = call.getArgument(1);
            activeIngestSources.entrySet().removeIf(entry -> sourceId.equals(entry.getValue().sourceId()));
            return null;
        }).when(media).kick(any(), any());
        when(media.getIngestPath(any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            return activeIngests.contains(id)
                    ? Optional.of(new MediaPathConfiguration("ingest/" + id, "publisher", false))
                    : Optional.empty();
        });
        when(media.getIngestSource(any())).thenAnswer(call ->
                Optional.ofNullable(activeIngestSources.get(call.getArgument(0))));
        doAnswer(call -> {
            activePlaybacks.add(call.getArgument(0));
            return null;
        }).when(media).ensurePlaybackPath(any(), any(), any());
        doAnswer(call -> {
            activePlaybacks.remove(call.getArgument(0));
            return null;
        }).when(media).deletePlaybackPath(any());
        when(media.getPlaybackPath(any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            return activePlaybacks.contains(id)
                    ? Optional.of(new MediaPathConfiguration("live/" + id, "rtmp://127.0.0.1/...", false))
                    : Optional.empty();
        });
        when(media.getPlaybackSource(any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            return activePlaybacks.contains(id)
                    ? Optional.of(new MediaPathStatus("live/" + id, true, UUID.randomUUID(), MediaSourceType.RTMP_SOURCE))
                    : Optional.empty();
        });
    }

    @Test
    void onlineOnActiveIngestOpensPlaybackRelayCreatesLiveSessionAndIssuesNoExpRelayJwt() throws Exception {
        ProvisionedChannel channel = provisionChannel();
        UUID sourceId = UUID.randomUUID();
        activeIngestSources.put(channel.ingestKeyId(),
                new MediaPathStatus("ingest/" + channel.ingestKeyId(), true, sourceId, MediaSourceType.RTMP_CONN));

        clock.now = BASE.plusSeconds(3);
        UUID eventId = UUID.randomUUID();
        MediaEventResponse accepted = lifecycleService.onOnline(new MediaIngestEventCommand(
                channel.ingestKeyId(), sourceId, MediaSourceType.RTMP_CONN, eventId,
                LocalDateTime.ofInstant(clock.now, ZoneOffset.UTC)));
        assertEquals(MediaEventOutcome.ACCEPTED, accepted.outcome());

        operationWorker.processDueOperations();

        ArgumentCaptor<String> relayJwtCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> sessionCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(media).ensurePlaybackPath(sessionCaptor.capture(), eq(channel.ingestKeyId()), relayJwtCaptor.capture());

        SignedJWT relayJwt = SignedJWT.parse(relayJwtCaptor.getValue());
        assertNull(relayJwt.getJWTClaimsSet().getExpirationTime());
        assertEquals("relay-internal", relayJwt.getJWTClaimsSet().getSubject());
        assertEquals(List.of(Map.of("action", "read", "path", "ingest/" + channel.ingestKeyId())),
                relayJwt.getJWTClaimsSet().getClaim("mediamtx_permissions"));

        UUID sessionId = sessionCaptor.getValue();
        StreamSessionEntity live = sessions.findById(sessionId).orElseThrow();
        assertEquals(StreamSessionStatus.LIVE, live.getStatus());
        assertEquals(MediaPathState.OPEN, live.getMediaPathState());
        assertEquals("/live/" + sessionId + "/index.m3u8", live.getPlaybackPath());
        assertEquals(channel.ingestKeyId(), live.getIngestKeyId());
        assertNull(live.getCreationRequestId());
        assertEquals(sourceId, live.getMediaSourceId());
        assertEquals(1, history.findByStreamIdOrderByCreatedAtAscIdAsc(sessionId).size());

        // Duplicate eventId returns existing outcome idempotently without creating another operation
        MediaEventResponse duplicate = lifecycleService.onOnline(new MediaIngestEventCommand(
                channel.ingestKeyId(), sourceId, MediaSourceType.RTMP_CONN, eventId,
                LocalDateTime.ofInstant(clock.now, ZoneOffset.UTC)));
        assertEquals(MediaEventOutcome.ACCEPTED, duplicate.outcome());
        assertEquals(sessionId, duplicate.streamId());
        assertEquals(2, operations.count());
    }

    @Test
    void onlinePartialMediaFailurePreservesSingleCandidateSessionIdAndCommitsOnRetry() {
        ProvisionedChannel channel = provisionChannel();
        UUID sourceId = UUID.randomUUID();
        activeIngestSources.put(channel.ingestKeyId(),
                new MediaPathStatus("ingest/" + channel.ingestKeyId(), true, sourceId, MediaSourceType.RTMP_CONN));

        AtomicInteger attempts = new AtomicInteger(0);
        doAnswer(call -> {
            if (attempts.incrementAndGet() == 1) {
                throw new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
            }
            activePlaybacks.add(call.getArgument(0));
            return null;
        }).when(media).ensurePlaybackPath(any(), eq(channel.ingestKeyId()), any());

        lifecycleService.onOnline(new MediaIngestEventCommand(
                channel.ingestKeyId(), sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        operationWorker.processDueOperations();

        assertEquals(0, sessions.count());
        List<StreamMediaOperationEntity> ops = operations.findAll();
        assertEquals(2, ops.size()); // PROVISION_INGEST + PROCESS_ONLINE
        StreamMediaOperationEntity failedOnline = ops.stream()
                .filter(op -> op.getCandidateSessionId() != null).findFirst().orElseThrow();
        UUID allocatedCandidateId = failedOnline.getCandidateSessionId();
        assertEquals(MediaOperationStatus.PENDING, failedOnline.getStatus());

        clock.now = clock.now.plusSeconds(2);
        operationWorker.processDueOperations();

        assertEquals(1, sessions.count());
        assertEquals(allocatedCandidateId, sessions.findAll().getFirst().getId());
        assertEquals(MediaOperationStatus.SUCCEEDED,
                operations.findById(failedOnline.getId()).orElseThrow().getStatus());
    }

    @Test
    void offlineToReconnectingClosesPlaybackPreservesThumbnailAndReconnectWithinGraceRestoresSameSession() {
        ProvisionedChannel channel = provisionChannel();
        UUID firstSource = UUID.randomUUID();
        activeIngestSources.put(channel.ingestKeyId(),
                new MediaPathStatus("ingest/" + channel.ingestKeyId(), true, firstSource, MediaSourceType.RTMP_CONN));

        lifecycleService.onOnline(new MediaIngestEventCommand(
                channel.ingestKeyId(), firstSource, MediaSourceType.RTMP_CONN, UUID.randomUUID(),
                LocalDateTime.ofInstant(clock.now, ZoneOffset.UTC)));
        operationWorker.processDueOperations();
        StreamSessionEntity live = sessions.findSlotOccupyingByChannelId(channel.channelId()).orElseThrow();
        UUID sessionId = live.getId();
        LocalDateTime startedAt = live.getStartedAt();
        ThumbnailState thumbnail = seedThumbnail(sessionId);

        // Disconnect at +10s
        clock.now = BASE.plusSeconds(10);
        activeIngestSources.remove(channel.ingestKeyId());
        lifecycleService.onOffline(new MediaIngestEventCommand(
                channel.ingestKeyId(), firstSource, MediaSourceType.RTMP_CONN, UUID.randomUUID(),
                LocalDateTime.ofInstant(clock.now, ZoneOffset.UTC)));
        operationWorker.processDueOperations();

        verify(media).deletePlaybackPath(sessionId);
        StreamSessionEntity reconnecting = sessions.findById(sessionId).orElseThrow();
        assertEquals(StreamSessionStatus.RECONNECTING, reconnecting.getStatus());
        assertEquals(MediaPathState.CLOSED, reconnecting.getMediaPathState());
        assertEquals(LocalDateTime.ofInstant(BASE.plusSeconds(70), ZoneOffset.UTC),
                reconnecting.getReconnectDeadlineAt());

        // Reconnect at +40s (within 60s grace) -> reuses same sessionId & startedAt & thumbnail state
        clock.now = BASE.plusSeconds(40);
        UUID secondSource = UUID.randomUUID();
        activeIngestSources.put(channel.ingestKeyId(),
                new MediaPathStatus("ingest/" + channel.ingestKeyId(), true, secondSource, MediaSourceType.RTMP_CONN));
        lifecycleService.onOnline(new MediaIngestEventCommand(
                channel.ingestKeyId(), secondSource, MediaSourceType.RTMP_CONN, UUID.randomUUID(),
                LocalDateTime.ofInstant(clock.now, ZoneOffset.UTC)));
        operationWorker.processDueOperations();

        StreamSessionEntity restored = sessions.findById(sessionId).orElseThrow();
        assertEquals(StreamSessionStatus.LIVE, restored.getStatus());
        assertEquals(MediaPathState.OPEN, restored.getMediaPathState());
        assertEquals(startedAt, restored.getStartedAt());
        assertEquals(secondSource, restored.getMediaSourceId());
        assertNull(restored.getDisconnectedAt());
        assertNull(restored.getReconnectDeadlineAt());
        assertThumbnailState(thumbnail, restored);
    }

    @Test
    void offlineWhenNewerSourceAlreadyOnlineIsIgnored() {
        ProvisionedChannel channel = provisionChannel();
        UUID oldSource = UUID.randomUUID();
        UUID newerSource = UUID.randomUUID();

        activeIngestSources.put(channel.ingestKeyId(),
                new MediaPathStatus("ingest/" + channel.ingestKeyId(), true, oldSource, MediaSourceType.RTMP_CONN));
        lifecycleService.onOnline(new MediaIngestEventCommand(
                channel.ingestKeyId(), oldSource, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        operationWorker.processDueOperations();
        UUID sessionId = sessions.findSlotOccupyingByChannelId(channel.channelId()).orElseThrow().getId();

        activeIngestSources.put(channel.ingestKeyId(),
                new MediaPathStatus("ingest/" + channel.ingestKeyId(), true, newerSource, MediaSourceType.RTMP_CONN));
        lifecycleService.onOffline(new MediaIngestEventCommand(
                channel.ingestKeyId(), oldSource, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        operationWorker.processDueOperations();

        assertEquals(StreamSessionStatus.LIVE, sessions.findById(sessionId).orElseThrow().getStatus());
        verify(media, never()).deletePlaybackPath(sessionId);
    }

    @Test
    void expireReconnectEndsAtExact60SecondsAndNextOnlineStartsFreshSessionId() {
        ProvisionedChannel channel = provisionChannel();
        UUID firstSource = UUID.randomUUID();
        activeIngestSources.put(channel.ingestKeyId(),
                new MediaPathStatus("ingest/" + channel.ingestKeyId(), true, firstSource, MediaSourceType.RTMP_CONN));

        lifecycleService.onOnline(new MediaIngestEventCommand(
                channel.ingestKeyId(), firstSource, MediaSourceType.RTMP_CONN, UUID.randomUUID(),
                LocalDateTime.ofInstant(clock.now, ZoneOffset.UTC)));
        operationWorker.processDueOperations();
        UUID s1 = sessions.findSlotOccupyingByChannelId(channel.channelId()).orElseThrow().getId();

        activeIngestSources.remove(channel.ingestKeyId());
        lifecycleService.onOffline(new MediaIngestEventCommand(
                channel.ingestKeyId(), firstSource, MediaSourceType.RTMP_CONN, UUID.randomUUID(),
                LocalDateTime.ofInstant(clock.now, ZoneOffset.UTC)));
        operationWorker.processDueOperations();

        clock.now = BASE.plusSeconds(59);
        assertFalse(closureService.expireReconnectIfDue(s1));
        assertEquals(StreamSessionStatus.RECONNECTING, sessions.findById(s1).orElseThrow().getStatus());

        clock.now = BASE.plusSeconds(60);
        assertTrue(closureService.expireReconnectIfDue(s1));
        assertEquals(StreamSessionStatus.ENDED, sessions.findById(s1).orElseThrow().getStatus());

        // Start streaming again -> new session S2
        clock.now = BASE.plusSeconds(70);
        UUID secondSource = UUID.randomUUID();
        activeIngestSources.put(channel.ingestKeyId(),
                new MediaPathStatus("ingest/" + channel.ingestKeyId(), true, secondSource, MediaSourceType.RTMP_CONN));
        lifecycleService.onOnline(new MediaIngestEventCommand(
                channel.ingestKeyId(), secondSource, MediaSourceType.RTMP_CONN, UUID.randomUUID(),
                LocalDateTime.ofInstant(clock.now, ZoneOffset.UTC)));
        operationWorker.processDueOperations();

        UUID s2 = sessions.findSlotOccupyingByChannelId(channel.channelId()).orElseThrow().getId();
        assertNotEquals(s1, s2);
        assertEquals(StreamSessionStatus.LIVE, sessions.findById(s2).orElseThrow().getStatus());
        assertEquals("/live/" + s2 + "/index.m3u8", sessions.findById(s2).orElseThrow().getPlaybackPath());
    }

    @Test
    void onlineRejectsIneligibleStreamerByBlockingConfigDeletingIngestAndKickingPublisher() {
        ProvisionedChannel channel = provisionChannel();
        when(eligibility.requirePublishContext(channel.ownerUserId(), channel.categoryId()))
                .thenThrow(new AppException(ErrorCode.STREAMER_NOT_ELIGIBLE));

        UUID sourceId = UUID.randomUUID();
        activeIngestSources.put(channel.ingestKeyId(),
                new MediaPathStatus("ingest/" + channel.ingestKeyId(), true, sourceId, MediaSourceType.RTMP_CONN));
        lifecycleService.onOnline(new MediaIngestEventCommand(
                channel.ingestKeyId(), sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        operationWorker.processDueOperations();

        verify(media).kick(MediaSourceType.RTMP_CONN, sourceId);
        verify(media).deleteIngestPath(channel.ingestKeyId());
        assertEquals(ChannelStreamConfigStatus.BLOCKED,
                configs.findById(channel.channelId()).orElseThrow().getStatus());
        assertEquals(0, sessions.count());
    }

    @Test
    void callbacksForRevokedOrUnknownKeyAndThumbnailsForEndingSessionsAreIgnored() {
        reset(media);
        AppException unknown = assertThrows(AppException.class, () -> lifecycleService.onOnline(
                new MediaIngestEventCommand(
                        UUID.randomUUID(), UUID.randomUUID(), MediaSourceType.RTMP_CONN, UUID.randomUUID(), null)));
        assertEquals(ErrorCode.STREAM_INGEST_KEY_NOT_FOUND, unknown.getErrorCode());
        verifyNoInteractions(media);

        installDefaultMediaStubs();
        ProvisionedChannel channel = provisionChannel();
        UUID sourceId = UUID.randomUUID();
        activeIngestSources.put(channel.ingestKeyId(),
                new MediaPathStatus("ingest/" + channel.ingestKeyId(), true, sourceId, MediaSourceType.RTMP_CONN));
        lifecycleService.onOnline(new MediaIngestEventCommand(
                channel.ingestKeyId(), sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        operationWorker.processDueOperations();

        UUID sessionId = sessions.findSlotOccupyingByChannelId(channel.channelId()).orElseThrow().getId();
        UUID jobId = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            StreamSessionEntity session = sessions.findByIdForUpdate(sessionId).orElseThrow();
            session.setEndRequested(true);
            session.setEndRequestedAt(LocalDateTime.ofInstant(clock.now, ZoneOffset.UTC));
            session.setEndReason("Ending");
            session.setThumbnailJobId(jobId);
            session.setThumbnailJobStatus(ThumbnailJobStatus.ACCEPTED);
            session.setThumbnailJobDeadlineAt(LocalDateTime.ofInstant(clock.now.plusSeconds(120), ZoneOffset.UTC));
            sessions.saveAndFlush(session);
        });

        var result = thumbnailResultService.apply(new ThumbnailResultRequest(
                jobId, sessionId, "livestream-thumbnails/" + sessionId + "/" + jobId + ".jpg",
                "image/jpeg", 1024L, 1280, 720));
        assertEquals(ThumbnailResultOutcome.IGNORED, result.outcome());

        setupService.resetStreamKey(channel.ownerUserId(), UUID.randomUUID().toString());
        operationWorker.processDueOperations();
        reset(media);

        MediaEventResponse revoked = lifecycleService.onOnline(new MediaIngestEventCommand(
                channel.ingestKeyId(), UUID.randomUUID(), MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        assertEquals(MediaEventOutcome.IGNORED, revoked.outcome());
        verifyNoInteractions(media);
    }

    @Test
    void expireReconnectRollsBackEndTransitionWhenMediaDeleteFails() {
        ProvisionedChannel channel = provisionChannel();
        UUID sourceId = UUID.randomUUID();
        activeIngestSources.put(channel.ingestKeyId(),
                new MediaPathStatus("ingest/" + channel.ingestKeyId(), true, sourceId, MediaSourceType.RTMP_CONN));
        lifecycleService.onOnline(new MediaIngestEventCommand(
                channel.ingestKeyId(), sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(),
                LocalDateTime.ofInstant(clock.now, ZoneOffset.UTC)));
        operationWorker.processDueOperations();
        UUID sessionId = sessions.findSlotOccupyingByChannelId(channel.channelId()).orElseThrow().getId();

        activeIngestSources.remove(channel.ingestKeyId());
        lifecycleService.onOffline(new MediaIngestEventCommand(
                channel.ingestKeyId(), sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(),
                LocalDateTime.ofInstant(clock.now, ZoneOffset.UTC)));
        operationWorker.processDueOperations();

        clock.now = BASE.plusSeconds(60);
        when(media.getPlaybackPath(sessionId)).thenReturn(Optional.of(
                new MediaPathConfiguration("live/" + sessionId, "rtmp://127.0.0.1/...", false)));
        assertThrows(AppException.class, () -> closureService.expireReconnectIfDue(sessionId));

        StreamSessionEntity reloaded = sessions.findById(sessionId).orElseThrow();
        assertEquals(StreamSessionStatus.RECONNECTING, reloaded.getStatus());
        assertTrue(reloaded.isEndRequested());
    }

    private ProvisionedChannel provisionChannel() {
        UUID ownerUserId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(eligibility.requireChannelContext(ownerUserId))
                .thenReturn(new StreamPublishContext(ownerUserId, streamerId, channelId, null));
        when(eligibility.requirePublishContext(ownerUserId, categoryId))
                .thenReturn(new StreamPublishContext(ownerUserId, streamerId, channelId, categoryId));
        setupService.saveSetup(ownerUserId, new SaveStreamSetupRequest("Lifecycle Stream", categoryId));
        operationWorker.processDueOperations();
        UUID ingestKeyId = configs.findById(channelId).orElseThrow().getCurrentIngestKeyId();
        return new ProvisionedChannel(ownerUserId, channelId, categoryId, ingestKeyId);
    }

    private ThumbnailState seedThumbnail(UUID sessionId) {
        LocalDateTime updatedAt = LocalDateTime.ofInstant(BASE.minusSeconds(120), ZoneOffset.UTC);
        LocalDateTime deadline = updatedAt.plusMinutes(4);
        UUID jobId = UUID.randomUUID();
        String objectKey = "livestream-thumbnails/" + sessionId + "/" + jobId + ".jpg";
        String url = "https://cdn.example.com/" + objectKey;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            StreamSessionEntity session = sessions.findByIdForUpdate(sessionId).orElseThrow();
            session.setThumbnailUrl(url);
            session.setThumbnailObjectKey(objectKey);
            session.setThumbnailUpdatedAt(updatedAt);
            session.setThumbnailJobId(jobId);
            session.setThumbnailJobStatus(ThumbnailJobStatus.SUCCEEDED);
            session.setThumbnailJobDeadlineAt(deadline);
            sessions.saveAndFlush(session);
        });
        return new ThumbnailState(url, objectKey, updatedAt, jobId, ThumbnailJobStatus.SUCCEEDED, deadline);
    }

    private void assertThumbnailState(ThumbnailState expected, StreamSessionEntity actual) {
        assertEquals(expected.url(), actual.getThumbnailUrl());
        assertEquals(expected.objectKey(), actual.getThumbnailObjectKey());
        assertEquals(expected.updatedAt(), actual.getThumbnailUpdatedAt());
        assertEquals(expected.jobId(), actual.getThumbnailJobId());
        assertEquals(expected.status(), actual.getThumbnailJobStatus());
        assertEquals(expected.deadline(), actual.getThumbnailJobDeadlineAt());
    }

    private record ProvisionedChannel(UUID ownerUserId, UUID channelId, UUID categoryId, UUID ingestKeyId) {}

    private record ThumbnailState(
            String url,
            String objectKey,
            LocalDateTime updatedAt,
            UUID jobId,
            ThumbnailJobStatus status,
            LocalDateTime deadline
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
