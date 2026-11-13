package com.reallive.streamservice.stream.service;

import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.adapter.media.MediaPathConfiguration;
import com.reallive.streamservice.stream.adapter.media.MediaPathStatus;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.dto.MediaIngestEventCommand;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import com.reallive.streamservice.stream.dto.request.SaveStreamSetupRequest;
import com.reallive.streamservice.stream.dto.response.StreamOperationResponse;
import com.reallive.streamservice.stream.dto.response.StreamSetupResponse;
import com.reallive.streamservice.stream.entity.ChannelStreamConfigEntity;
import com.reallive.streamservice.stream.entity.ChannelStreamCredentialEntity;
import com.reallive.streamservice.stream.entity.StreamMediaOperationEntity;
import com.reallive.streamservice.stream.entity.enums.ChannelStreamConfigStatus;
import com.reallive.streamservice.stream.entity.enums.CredentialStatus;
import com.reallive.streamservice.stream.entity.enums.MediaOperationStatus;
import com.reallive.streamservice.stream.entity.enums.MediaOperationType;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
import com.reallive.streamservice.stream.repository.ChannelStreamCredentialRepository;
import com.reallive.streamservice.stream.repository.StreamMediaOperationRepository;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.repository.StreamStatusHistoryRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class StreamProvisioningPostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final Instant BASE = Instant.parse("2026-10-26T08:00:00Z");

    @Autowired private StreamSetupService setupService;
    @Autowired private StreamSessionService sessionService;
    @Autowired private StreamMediaLifecycleService lifecycleService;
    @Autowired private StreamMediaOperationWorker worker;
    @Autowired private ChannelStreamConfigRepository configs;
    @Autowired private ChannelStreamCredentialRepository credentials;
    @Autowired private StreamMediaOperationRepository operations;
    @Autowired private StreamSessionRepository sessions;
    @Autowired private StreamStatusHistoryRepository history;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @MockitoBean private StreamEligibilityService eligibility;
    @MockitoBean private MediaServerAdapter media;

    private final Set<UUID> configuredIngests = ConcurrentHashMap.newKeySet();
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
        configuredIngests.clear();
        activePlaybacks.clear();
        activeIngestSources.clear();

        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            configuredIngests.add(call.getArgument(0));
            return null;
        }).when(media).ensureIngestPath(any());

        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            UUID id = call.getArgument(0);
            configuredIngests.remove(id);
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
            if (!configuredIngests.contains(id)) {
                return Optional.empty();
            }
            return Optional.of(new MediaPathConfiguration("ingest/" + id, "publisher", false));
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
    void lazyGetSetupCreatesDraftWithoutCredentialsOrMediaAndSaveFirstProvisionsPersistentKey() throws Exception {
        Owner owner = owner();
        StreamSetupResponse draft = setupService.getOrCreateSetup(owner.user());

        assertEquals(owner.channel(), draft.channelId());
        assertEquals(ChannelStreamConfigStatus.DRAFT, draft.status());
        assertEquals("", draft.title());
        assertNull(draft.categoryId());
        assertEquals(0L, draft.generation());
        assertNull(draft.streamKey());
        assertFalse(draft.publishReady());
        assertEquals(0, credentials.count());
        verifyNoInteractions(media);

        // Save first valid setup
        StreamSetupResponse provisioning = setupService.saveSetup(
                owner.user(), new SaveStreamSetupRequest("  Persistent Stream  ", owner.category()));
        assertEquals(ChannelStreamConfigStatus.PROVISIONING, provisioning.status());
        assertEquals(1L, provisioning.generation());
        assertNotNull(provisioning.operationId());
        assertNull(provisioning.streamKey());
        assertFalse(provisioning.publishReady());

        // Worker provisions ingest/{ingestKeyId} and promotes credential/config to ACTIVE
        worker.processDueOperations();

        StreamSetupResponse active = setupService.getOrCreateSetup(owner.user());
        assertEquals(ChannelStreamConfigStatus.ACTIVE, active.status());
        assertEquals("Persistent Stream", active.title());
        assertEquals(owner.category(), active.categoryId());
        assertEquals(1L, active.generation());
        assertTrue(active.publishReady());
        assertNotNull(active.streamKey());
        assertFalse(active.toString().contains(active.streamKey()));

        String[] parts = active.streamKey().split("\\?token=");
        assertEquals(2, parts.length);
        UUID ingestKeyId = UUID.fromString(parts[0]);
        var jwt = SignedJWT.parse(parts[1]);
        assertNull(jwt.getJWTClaimsSet().getExpirationTime());
        assertEquals(List.of(Map.of("action", "publish", "path", "ingest/" + ingestKeyId)),
                jwt.getJWTClaimsSet().getClaim("mediamtx_permissions"));

        // Second GET returns exact same persistent stream key
        assertEquals(active.streamKey(), setupService.getOrCreateSetup(owner.user()).streamKey());
        // Technical RelayRead JWT has read only on ingest/{ingestKeyId} and no exp
        ChannelStreamCredentialEntity cred = credentials.findByIngestKeyId(ingestKeyId).orElseThrow();
        assertEquals(CredentialStatus.ACTIVE, cred.getStatus());
        assertTrue(cred.getEncryptedPublishJwt().startsWith("v1:"));
        assertTrue(cred.getEncryptedRelayReadJwt().startsWith("v1:"));
    }

    @Test
    void activeSetupDetectsMissingMediaPathReadOnlyAndEnqueuesRecoveryWithoutRegeneratingKey() {
        Owner owner = owner();
        setupService.saveSetup(owner.user(), new SaveStreamSetupRequest("Ready Stream", owner.category()));
        worker.processDueOperations();

        StreamSetupResponse initial = setupService.getOrCreateSetup(owner.user());
        assertTrue(initial.publishReady());
        UUID ingestKeyId = configs.findById(owner.channel()).orElseThrow().getCurrentIngestKeyId();

        // Simulate MediaMTX restart or vanished path
        configuredIngests.remove(ingestKeyId);
        StreamSetupResponse degraded = setupService.getOrCreateSetup(owner.user());
        assertFalse(degraded.publishReady());
        assertEquals(initial.streamKey(), degraded.streamKey());
        assertNotNull(degraded.operationId());

        worker.processDueOperations();
        StreamSetupResponse recovered = setupService.getOrCreateSetup(owner.user());
        assertTrue(recovered.publishReady());
        assertEquals(initial.streamKey(), recovered.streamKey());
    }

    @Test
    void concurrentFirstGetOrCreateSetupCreatesSingleDraftRowSafely() throws Exception {
        Owner owner = owner();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var f1 = executor.submit(() -> { start.await(); return setupService.getOrCreateSetup(owner.user()); });
            var f2 = executor.submit(() -> { start.await(); return setupService.getOrCreateSetup(owner.user()); });
            start.countDown();
            StreamSetupResponse r1 = f1.get(5, TimeUnit.SECONDS);
            StreamSetupResponse r2 = f2.get(5, TimeUnit.SECONDS);
            assertEquals(owner.channel(), r1.channelId());
            assertEquals(owner.channel(), r2.channelId());
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from channel_stream_configs", Integer.class));
    }

    @Test
    void saveWhileActiveUpdatesConfigAndCurrentLiveSessionWithoutDisruptingMedia() {
        Owner owner = owner();
        setupService.saveSetup(owner.user(), new SaveStreamSetupRequest("Initial Title", owner.category()));
        worker.processDueOperations();
        UUID ingestKeyId = configs.findById(owner.channel()).orElseThrow().getCurrentIngestKeyId();

        UUID sourceId = UUID.randomUUID();
        activeIngestSources.put(ingestKeyId,
                new MediaPathStatus("ingest/" + ingestKeyId, true, sourceId, MediaSourceType.RTMP_CONN));

        lifecycleService.onOnline(new MediaIngestEventCommand(
                ingestKeyId, sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        worker.processDueOperations();
        clearInvocations(media);

        UUID newCategory = UUID.randomUUID();
        StreamSetupResponse updated = setupService.saveSetup(
                owner.user(), new SaveStreamSetupRequest("Updated Live Title", newCategory));
        assertEquals("Updated Live Title", updated.title());
        assertEquals(newCategory, updated.categoryId());
        assertEquals("Updated Live Title", sessionService.current(owner.user()).title());
        assertEquals(newCategory, sessionService.current(owner.user()).categoryId());
        verify(media, never()).ensureIngestPath(any());
        verify(media, never()).ensurePlaybackPath(any(), any(), any());
    }

    @Test
    void resetWhileLiveRevokesOldKeyKicksPublisherEndsSessionAndProvisionsGenerationTwo() {
        Owner owner = owner();
        setupService.saveSetup(owner.user(), new SaveStreamSetupRequest("Stream Gen 1", owner.category()));
        worker.processDueOperations();
        String firstKey = setupService.getOrCreateSetup(owner.user()).streamKey();
        UUID firstIngestKeyId = configs.findById(owner.channel()).orElseThrow().getCurrentIngestKeyId();

        UUID sourceId = UUID.randomUUID();
        activeIngestSources.put(firstIngestKeyId,
                new MediaPathStatus("ingest/" + firstIngestKeyId, true, sourceId, MediaSourceType.RTMP_CONN));
        lifecycleService.onOnline(new MediaIngestEventCommand(
                firstIngestKeyId, sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        worker.processDueOperations();

        UUID firstSessionId = sessions.findSlotOccupyingByChannelId(owner.channel()).orElseThrow().getId();
        clearInvocations(media);

        // Initiate reset (idempotent by key)
        StreamOperationResponse resetOp = setupService.resetStreamKey(owner.user(), "reset-live-1");
        StreamOperationResponse sameKeyReplay = setupService.resetStreamKey(owner.user(), "reset-live-1");
        assertEquals(resetOp.operationId(), sameKeyReplay.operationId());
        assertEquals(MediaOperationType.RESET_KEY, resetOp.operationType());

        // While ROTATING: old cred revoked, gen 2 pending, saveSetup blocked, different reset key joins existing RESET_KEY
        assertEquals(ChannelStreamConfigStatus.ROTATING, configs.findById(owner.channel()).orElseThrow().getStatus());
        assertEquals(CredentialStatus.REVOKED,
                credentials.findByIngestKeyId(firstIngestKeyId).orElseThrow().getStatus());
        error(ErrorCode.STREAM_KEY_ROTATION_IN_PROGRESS, () -> setupService.saveSetup(
                owner.user(), new SaveStreamSetupRequest("Blocked during rotate", owner.category())));
        assertEquals(resetOp.operationId(),
                setupService.resetStreamKey(owner.user(), "different-reset-key").operationId());

        // Worker closes old ingest & playback and opens generation 2 ingest
        worker.processDueOperations();

        verify(media).kick(MediaSourceType.RTMP_CONN, sourceId);
        verify(media).deleteIngestPath(firstIngestKeyId);
        verify(media).deletePlaybackPath(firstSessionId);
        assertEquals(StreamSessionStatus.ENDED, sessions.findById(firstSessionId).orElseThrow().getStatus());
        assertNull(sessionService.current(owner.user()));

        StreamSetupResponse afterReset = setupService.getOrCreateSetup(owner.user());
        assertEquals(ChannelStreamConfigStatus.ACTIVE, afterReset.status());
        assertEquals(2L, afterReset.generation());
        assertTrue(afterReset.publishReady());
        assertNotEquals(firstKey, afterReset.streamKey());
        assertEquals(MediaOperationStatus.SUCCEEDED,
                setupService.getOperation(owner.user(), resetOp.operationId()).status());
    }

    @Test
    void partialFailureDuringResetRetriesWithoutLeakingOrDuplicatingKeyAndCompletes() {
        Owner owner = owner();
        setupService.saveSetup(owner.user(), new SaveStreamSetupRequest("Retry Reset", owner.category()));
        worker.processDueOperations();
        UUID gen1IngestKey = configs.findById(owner.channel()).orElseThrow().getCurrentIngestKeyId();

        AtomicInteger deleteAttempts = new AtomicInteger(0);
        doAnswer(call -> {
            if (deleteAttempts.incrementAndGet() == 1) {
                throw new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
            }
            configuredIngests.remove(call.getArgument(0));
            return null;
        }).when(media).deleteIngestPath(gen1IngestKey);

        UUID opId = setupService.resetStreamKey(owner.user(), "retry-reset").operationId();
        worker.processDueOperations();

        StreamMediaOperationEntity failedOnce = operations.findById(opId).orElseThrow();
        assertEquals(MediaOperationStatus.PENDING, failedOnce.getStatus());
        assertEquals(1, failedOnce.getAttemptCount());
        assertEquals(ChannelStreamConfigStatus.ROTATING, configs.findById(owner.channel()).orElseThrow().getStatus());
        assertEquals(2, credentials.findByChannelIdOrderByGenerationDesc(owner.channel()).size());

        // Advance beyond first 1s backoff and retry
        clock.now = clock.now.plusSeconds(2);
        worker.processDueOperations();

        assertEquals(MediaOperationStatus.SUCCEEDED, operations.findById(opId).orElseThrow().getStatus());
        assertEquals(ChannelStreamConfigStatus.ACTIVE, configs.findById(owner.channel()).orElseThrow().getStatus());
        assertEquals(2L, configs.findById(owner.channel()).orElseThrow().getCurrentCredentialGeneration());
        verify(media, times(2)).deleteIngestPath(gen1IngestKey);
    }

    @Test
    void saveAfterBlockedPreservesKeyAndReenablesProvisioning() {
        Owner owner = owner();
        setupService.saveSetup(owner.user(), new SaveStreamSetupRequest("Original", owner.category()));
        worker.processDueOperations();
        String originalKey = setupService.getOrCreateSetup(owner.user()).streamKey();
        ChannelStreamConfigEntity config = configs.findById(owner.channel()).orElseThrow();
        UUID ingestKeyId = config.getCurrentIngestKeyId();

        // Simulate eligibility rejection during start/online marking config BLOCKED
        when(eligibility.requirePublishContext(owner.user(), owner.category()))
                .thenThrow(new AppException(ErrorCode.STREAMER_NOT_ELIGIBLE));
        UUID sourceId = UUID.randomUUID();
        activeIngestSources.put(ingestKeyId,
                new MediaPathStatus("ingest/" + ingestKeyId, true, sourceId, MediaSourceType.RTMP_CONN));
        lifecycleService.onOnline(new MediaIngestEventCommand(
                ingestKeyId, sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        worker.processDueOperations();

        ChannelStreamConfigEntity blocked = configs.findById(owner.channel()).orElseThrow();
        assertEquals(ChannelStreamConfigStatus.BLOCKED, blocked.getStatus());
        error(ErrorCode.STREAM_CONFIG_BLOCKED, () -> setupService.resetStreamKey(owner.user(), "while-blocked"));

        // Eligibility restored -> streamer saves setup again -> key preserved
        doReturn(new StreamPublishContext(owner.user(), owner.streamer(), owner.channel(), owner.category()))
                .when(eligibility)
                .requirePublishContext(owner.user(), owner.category());
        setupService.saveSetup(owner.user(), new SaveStreamSetupRequest("Restored Stream", owner.category()));
        worker.processDueOperations();

        StreamSetupResponse restored = setupService.getOrCreateSetup(owner.user());
        assertEquals(ChannelStreamConfigStatus.ACTIVE, restored.status());
        assertEquals(1L, restored.generation());
        assertEquals(originalKey, restored.streamKey());
        assertTrue(restored.publishReady());
    }

    @Test
    void provisioningRetrySucceedsAfterTemporaryMediaOutage() {
        Owner owner = owner();
        doThrow(new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE)).when(media).ensureIngestPath(any());

        UUID opId = setupService.saveSetup(owner.user(), new SaveStreamSetupRequest("Retry Setup", owner.category()))
                .operationId();
        worker.processDueOperations();
        assertEquals(MediaOperationStatus.PENDING, operations.findById(opId).orElseThrow().getStatus());
        assertEquals(ChannelStreamConfigStatus.PROVISIONING, configs.findById(owner.channel()).orElseThrow().getStatus());

        doAnswer(call -> {
            configuredIngests.add(call.getArgument(0));
            return null;
        }).when(media).ensureIngestPath(any());
        clock.now = clock.now.plusSeconds(2);
        worker.processDueOperations();

        assertEquals(MediaOperationStatus.SUCCEEDED, operations.findById(opId).orElseThrow().getStatus());
        assertTrue(setupService.getOrCreateSetup(owner.user()).publishReady());
    }

    @Test
    void resetWaitsForInflightRecoveryBeforeRetiringOldIngest() throws Exception {
        Owner owner = owner();
        setupService.saveSetup(owner.user(), new SaveStreamSetupRequest("Recovery race", owner.category()));
        worker.processDueOperations();
        UUID oldIngest = configs.findById(owner.channel()).orElseThrow().getCurrentIngestKeyId();
        configuredIngests.remove(oldIngest);
        UUID repairId = setupService.getOrCreateSetup(owner.user()).operationId();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            UUID ingest = call.getArgument(0);
            if (oldIngest.equals(ingest)) {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            configuredIngests.add(ingest);
            return null;
        }).when(media).ensureIngestPath(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var repair = executor.submit(() -> worker.executeSingleOperation(repairId));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var reset = setupService.resetStreamKey(owner.user(), "inflight-repair-reset");
                assertFalse(worker.executeSingleOperation(reset.operationId()));
                release.countDown();
                assertTrue(repair.get(5, TimeUnit.SECONDS));
                assertTrue(worker.executeSingleOperation(reset.operationId()));
                assertEquals(MediaOperationStatus.SUCCEEDED,
                        operations.findById(reset.operationId()).orElseThrow().getStatus());
                assertFalse(configuredIngests.contains(oldIngest));
                assertTrue(setupService.getOrCreateSetup(owner.user()).publishReady());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void mediaOwnershipIsExclusiveAcrossDatabaseSessionsAndReleasedOnUnlock() throws Exception {
        Owner owner = owner();
        UUID operationId = setupService.saveSetup(owner.user(),
                new SaveStreamSetupRequest("External owner", owner.category())).operationId();
        long key = owner.channel().getMostSignificantBits() ^ owner.channel().getLeastSignificantBits();
        try (var connection = jdbc.getDataSource().getConnection();
             var statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, key);
            statement.execute();
            assertFalse(worker.executeSingleOperation(operationId));
            verify(media, never()).ensureIngestPath(any());
            try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                unlock.setLong(1, key);
                unlock.execute();
            }
        }
        assertTrue(worker.executeSingleOperation(operationId));
        assertEquals(MediaOperationStatus.SUCCEEDED, operations.findById(operationId).orElseThrow().getStatus());
    }

    private Owner owner() {
        Owner owner = new Owner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(eligibility.requireChannelContext(owner.user()))
                .thenReturn(new StreamPublishContext(owner.user(), owner.streamer(), owner.channel(), null));
        when(eligibility.requirePublishContext(eq(owner.user()), any()))
                .thenAnswer(call -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    return new StreamPublishContext(owner.user(), owner.streamer(), owner.channel(), call.getArgument(1));
                });
        return owner;
    }

    private void error(ErrorCode code, Executable action) {
        assertEquals(code, assertThrows(AppException.class, action).getErrorCode());
    }

    private record Owner(UUID user, UUID streamer, UUID channel, UUID category) {}

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
