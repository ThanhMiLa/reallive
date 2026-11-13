package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.adapter.media.MediaPage;
import com.reallive.streamservice.stream.adapter.media.MediaPathConfiguration;
import com.reallive.streamservice.stream.adapter.media.MediaPathStatus;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.adapter.media.MediaServerInfo;
import com.reallive.streamservice.stream.dto.MediaEventOutcome;
import com.reallive.streamservice.stream.dto.MediaIngestEventCommand;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import com.reallive.streamservice.stream.dto.request.SaveStreamSetupRequest;
import com.reallive.streamservice.stream.entity.StreamMediaOperationEntity;
import com.reallive.streamservice.stream.entity.StreamMediaRuntimeStateEntity;
import com.reallive.streamservice.stream.entity.enums.MediaOperationStatus;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
import com.reallive.streamservice.stream.repository.ChannelStreamCredentialRepository;
import com.reallive.streamservice.stream.repository.StreamMediaOperationRepository;
import com.reallive.streamservice.stream.repository.StreamMediaRuntimeStateRepository;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.repository.StreamStatusHistoryRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class StreamConcurrencyAndTransactionPostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final Instant BASE = Instant.parse("2026-10-25T12:00:00Z");

    @Autowired private StreamSetupService setupService;
    @Autowired private StreamMediaLifecycleService lifecycleService;
    @Autowired private StreamMediaOperationWorker operationWorker;
    @Autowired private StreamMediaReconciler reconciler;
    @Autowired private ChannelStreamConfigRepository configs;
    @Autowired private ChannelStreamCredentialRepository credentials;
    @Autowired private StreamMediaOperationRepository operations;
    @Autowired private StreamMediaRuntimeStateRepository runtimeStates;
    @Autowired private StreamSessionRepository sessions;
    @Autowired private StreamStatusHistoryRepository history;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ControlledClock clock;
    @Autowired private ApplicationContext context;

    @MockitoBean private StreamEligibilityService eligibility;
    @MockitoBean private MediaServerAdapter media;

    private final Set<UUID> activeIngests = ConcurrentHashMap.newKeySet();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        configureDatabase(registry, SCHEMA);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        dropSchema(SCHEMA);
    }

    @BeforeEach
    void resetState() {
        history.deleteAll();
        operations.deleteAll();
        sessions.deleteAll();
        credentials.deleteAll();
        configs.deleteAll();
        runtimeStates.deleteAll();
        clock.instant = BASE;
        activeIngests.clear();

        doAnswer(call -> {
            activeIngests.add(call.getArgument(0));
            return null;
        }).when(media).ensureIngestPath(any());
        doAnswer(call -> {
            activeIngests.remove(call.getArgument(0));
            return null;
        }).when(media).deleteIngestPath(any());
        when(media.getIngestPath(any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            return activeIngests.contains(id)
                    ? Optional.of(new MediaPathConfiguration("ingest/" + id, "publisher", false))
                    : Optional.empty();
        });
        when(media.getIngestSource(any())).thenReturn(Optional.empty());
        when(media.getPlaybackPath(any())).thenReturn(Optional.empty());
        when(media.getPlaybackSource(any())).thenReturn(Optional.empty());
        when(media.getServerInfo()).thenReturn(new MediaServerInfo("1.21.2", "2026-10-25T12:00:00Z"));
        when(media.listConfigurations(anyInt(), anyInt())).thenReturn(new MediaPage<>(0, 1, List.of()));
        when(media.listPaths(anyInt(), anyInt())).thenReturn(new MediaPage<>(0, 1, List.of()));
    }

    @Test
    void concurrentOnlineWebhooksProduceOnlyOneLiveSessionAndCancelDuplicateCandidateAllocation() throws Exception {
        Owner owner = owner();
        setupService.saveSetup(owner.user(), new SaveStreamSetupRequest("Race Stream", owner.category()));
        operationWorker.processDueOperations();
        UUID ingestKeyId = configs.findById(owner.channel()).orElseThrow().getCurrentIngestKeyId();

        UUID sourceId = UUID.randomUUID();
        when(media.getIngestSource(ingestKeyId)).thenReturn(Optional.of(
                new MediaPathStatus("ingest/" + ingestKeyId, true, sourceId, MediaSourceType.RTMP_CONN)));
        when(media.getPlaybackSource(any())).thenAnswer(call -> Optional.of(
                new MediaPathStatus("live/" + call.getArgument(0), true, UUID.randomUUID(), MediaSourceType.RTMP_SOURCE)));

        var r1 = lifecycleService.onOnline(new MediaIngestEventCommand(
                ingestKeyId, sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        var r2 = lifecycleService.onOnline(new MediaIngestEventCommand(
                ingestKeyId, sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        assertEquals(MediaEventOutcome.ACCEPTED, r1.outcome());
        assertEquals(MediaEventOutcome.ACCEPTED, r2.outcome());

        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var f1 = executor.submit(() -> { ready.countDown(); go.await(); return operationWorker.processDueOperations(); });
            var f2 = executor.submit(() -> { ready.countDown(); go.await(); return operationWorker.processDueOperations(); });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        }

        assertEquals(1, sessions.count());
        assertEquals(StreamSessionStatus.LIVE, sessions.findAll().getFirst().getStatus());
        assertEquals(0, operations.findDueOperationIds(LocalDateTime.now(clock).plusSeconds(1),
                org.springframework.data.domain.PageRequest.of(0, 10)).size());
    }

    @Test
    void expiredLeaseOperationIsReclaimedByWorkerAndLegacyTaskInfrastructureIsAbsent() {
        Owner owner = owner();
        setupService.saveSetup(owner.user(), new SaveStreamSetupRequest("Lease Recovery", owner.category()));
        operationWorker.processDueOperations();
        UUID ingestKeyId = configs.findById(owner.channel()).orElseThrow().getCurrentIngestKeyId();

        UUID sourceId = UUID.randomUUID();
        when(media.getIngestSource(ingestKeyId)).thenReturn(Optional.of(
                new MediaPathStatus("ingest/" + ingestKeyId, true, sourceId, MediaSourceType.RTMP_CONN)));
        when(media.getPlaybackSource(any())).thenAnswer(call -> Optional.of(
                new MediaPathStatus("live/" + call.getArgument(0), true, UUID.randomUUID(), MediaSourceType.RTMP_SOURCE)));

        lifecycleService.onOnline(new MediaIngestEventCommand(
                ingestKeyId, sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        StreamMediaOperationEntity onlineOp = operations.findAll().stream()
                .filter(op -> op.getStatus() == MediaOperationStatus.PENDING)
                .findFirst().orElseThrow();

        // Simulate crashed worker holding an expired lease
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            StreamMediaOperationEntity op = operations.findByIdForUpdate(onlineOp.getId()).orElseThrow();
            op.setStatus(MediaOperationStatus.RUNNING);
            op.setLeaseOwner("crashed-pod");
            op.setLeaseUntil(LocalDateTime.ofInstant(clock.instant().minusSeconds(5), ZoneOffset.UTC));
            operations.saveAndFlush(op);
        });

        operationWorker.processDueOperations();

        assertEquals(MediaOperationStatus.SUCCEEDED,
                operations.findById(onlineOp.getId()).orElseThrow().getStatus());
        assertEquals(1, sessions.count());
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema=? and table_name='stream_media_sync_tasks'",
                Integer.class, SCHEMA));
        assertTrue(Arrays.stream(context.getBeanDefinitionNames())
                .noneMatch(name -> name.toLowerCase().contains("mediasynctask")));
    }

    @Test
    void reconcilerDetectsRestartCleansOrphanedPathsBeforeRestoringActiveIngestsAndSynthesizesMissedOffline() {
        Owner owner = owner();
        setupService.saveSetup(owner.user(), new SaveStreamSetupRequest("Reconcile Stream", owner.category()));
        operationWorker.processDueOperations();
        UUID ingestKeyId = configs.findById(owner.channel()).orElseThrow().getCurrentIngestKeyId();

        UUID liveSourceId = UUID.randomUUID();
        when(media.getIngestSource(ingestKeyId)).thenReturn(Optional.of(
                new MediaPathStatus("ingest/" + ingestKeyId, true, liveSourceId, MediaSourceType.RTMP_CONN)));
        when(media.getPlaybackSource(any())).thenAnswer(call -> Optional.of(
                new MediaPathStatus("live/" + call.getArgument(0), true, UUID.randomUUID(), MediaSourceType.RTMP_SOURCE)));
        lifecycleService.onOnline(new MediaIngestEventCommand(
                ingestKeyId, liveSourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        operationWorker.processDueOperations();
        UUID liveSessionId = sessions.findSlotOccupyingByChannelId(owner.channel()).orElseThrow().getId();

        // Seed previous serverStartedAt and simulate MediaMTX restart dropping ingest + adding orphans
        runtimeStates.saveAndFlush(StreamMediaRuntimeStateEntity.builder()
                .id(StreamMediaRuntimeStateEntity.PRIMARY_ID)
                .serverStartedAt("2026-10-25T11:00:00Z")
                .lastReconciledAt(LocalDateTime.ofInstant(clock.instant().minusSeconds(60), ZoneOffset.UTC))
                .updatedAt(LocalDateTime.ofInstant(clock.instant().minusSeconds(60), ZoneOffset.UTC))
                .build());
        activeIngests.remove(ingestKeyId);
        UUID orphanIngest = UUID.randomUUID();
        UUID orphanSession = UUID.randomUUID();

        when(media.getServerInfo()).thenReturn(new MediaServerInfo("1.21.2", "2026-10-25T12:00:05Z"));
        when(media.listConfigurations(0, 100)).thenReturn(new MediaPage<>(2, 1, List.of(
                new MediaPathConfiguration("ingest/" + orphanIngest, "publisher", false),
                new MediaPathConfiguration("live/" + orphanSession, "rtmp://127.0.0.1:1935/...", false)
        )));
        when(media.getIngestSource(ingestKeyId)).thenReturn(Optional.empty());
        when(media.getPlaybackSource(liveSessionId)).thenReturn(Optional.empty());

        reconciler.reconcileOnce();

        InOrder order = inOrder(media);
        order.verify(media).deleteIngestPath(orphanIngest);
        order.verify(media).ensureIngestPath(ingestKeyId);
        verify(media).deletePlaybackPath(orphanSession);

        // Server restart cannot determine disconnect instant safely -> ends in-flight session
        operationWorker.processDueOperations();
        assertEquals(StreamSessionStatus.ENDED, sessions.findById(liveSessionId).orElseThrow().getStatus());
        assertEquals("Media server restarted", sessions.findById(liveSessionId).orElseThrow().getEndReason());
        assertEquals("2026-10-25T12:00:05Z",
                runtimeStates.findById(StreamMediaRuntimeStateEntity.PRIMARY_ID).orElseThrow().getServerStartedAt());

        // Same MediaMTX instance later misses an offline webhook -> reconciler synthesizes PROCESS_OFFLINE -> RECONNECTING
        UUID nextSourceId = UUID.randomUUID();
        when(media.getIngestSource(ingestKeyId)).thenReturn(Optional.of(
                new MediaPathStatus("ingest/" + ingestKeyId, true, nextSourceId, MediaSourceType.RTMP_CONN)));
        when(media.getPlaybackSource(any())).thenAnswer(call -> Optional.of(
                new MediaPathStatus("live/" + call.getArgument(0), true, UUID.randomUUID(), MediaSourceType.RTMP_SOURCE)));
        lifecycleService.onOnline(new MediaIngestEventCommand(
                ingestKeyId, nextSourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        operationWorker.processDueOperations();
        UUID secondSessionId = sessions.findSlotOccupyingByChannelId(owner.channel()).orElseThrow().getId();
        assertEquals(StreamSessionStatus.LIVE, sessions.findById(secondSessionId).orElseThrow().getStatus());

        when(media.listConfigurations(0, 100)).thenReturn(new MediaPage<>(1, 1, List.of(
                new MediaPathConfiguration("ingest/" + ingestKeyId, "publisher", false)
        )));
        when(media.getIngestSource(ingestKeyId)).thenReturn(Optional.empty());
        when(media.getPlaybackSource(secondSessionId)).thenReturn(Optional.empty());

        reconciler.reconcileOnce();
        operationWorker.processDueOperations();
        assertEquals(StreamSessionStatus.RECONNECTING, sessions.findById(secondSessionId).orElseThrow().getStatus());
    }

    private Owner owner() {
        Owner owner = new Owner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(eligibility.requireChannelContext(owner.user()))
                .thenReturn(new StreamPublishContext(owner.user(), owner.streamer(), owner.channel(), null));
        when(eligibility.requirePublishContext(owner.user(), owner.category()))
                .thenReturn(new StreamPublishContext(owner.user(), owner.streamer(), owner.channel(), owner.category()));
        return owner;
    }

    private record Owner(UUID user, UUID streamer, UUID channel, UUID category) {}

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        ControlledClock controlledClock() {
            return new ControlledClock();
        }
    }

    static class ControlledClock extends Clock {
        private volatile Instant instant = BASE;

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
