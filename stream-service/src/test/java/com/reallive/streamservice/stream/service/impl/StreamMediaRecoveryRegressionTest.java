package com.reallive.streamservice.stream.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.reallive.streamservice.stream.adapter.crypto.CredentialCipher;
import com.reallive.streamservice.stream.adapter.media.MediaPage;
import com.reallive.streamservice.stream.adapter.media.MediaPathConfiguration;
import com.reallive.streamservice.stream.adapter.media.MediaPathStatus;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.adapter.media.MediaServerInfo;
import com.reallive.streamservice.stream.entity.ChannelStreamConfigEntity;
import com.reallive.streamservice.stream.entity.ChannelStreamCredentialEntity;
import com.reallive.streamservice.stream.entity.StreamMediaOperationEntity;
import com.reallive.streamservice.stream.entity.StreamMediaRuntimeStateEntity;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.ChannelStreamConfigStatus;
import com.reallive.streamservice.stream.entity.enums.CredentialStatus;
import com.reallive.streamservice.stream.entity.enums.MediaOperationStatus;
import com.reallive.streamservice.stream.entity.enums.MediaOperationType;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
import com.reallive.streamservice.stream.repository.ChannelStreamCredentialRepository;
import com.reallive.streamservice.stream.repository.StreamMediaOperationRepository;
import com.reallive.streamservice.stream.repository.StreamMediaRuntimeStateRepository;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.repository.StreamStatusHistoryRepository;
import com.reallive.streamservice.stream.service.StreamEligibilityService;
import com.reallive.streamservice.stream.service.StreamMediaOperationWorker;
import com.reallive.streamservice.stream.service.StreamViewerPresenceService;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class StreamMediaRecoveryRegressionTest {
    static class MutableClock extends Clock {
        Instant t = Instant.parse("2026-11-13T00:00:00Z");

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId z) {
            return this;
        }

        public Instant instant() {
            return t;
        }
    }

    static class Fixture {
        MutableClock clock = new MutableClock();
        UUID channelId = UUID.randomUUID(), ingest = UUID.randomUUID(), source = UUID.randomUUID();
        ChannelStreamConfigRepository configs = mock(ChannelStreamConfigRepository.class);
        ChannelStreamCredentialRepository creds = mock(ChannelStreamCredentialRepository.class);
        StreamMediaOperationRepository ops = mock(StreamMediaOperationRepository.class);
        StreamSessionRepository sessions = mock(StreamSessionRepository.class);
        MediaServerAdapter media = mock(MediaServerAdapter.class);
        CredentialCipher cipher = mock(CredentialCipher.class);
        AtomicReference<StreamSessionEntity> session = new AtomicReference<>();
        Set<UUID> paths = new HashSet<>();
        StreamMediaOperationEntity op;
        ChannelStreamConfigEntity config =
                ChannelStreamConfigEntity.builder()
                        .channelId(channelId)
                        .ownerUserId(UUID.randomUUID())
                        .streamerId(UUID.randomUUID())
                        .title("Review")
                        .categoryId(UUID.randomUUID())
                        .visibility(StreamVisibility.PUBLIC)
                        .status(ChannelStreamConfigStatus.ACTIVE)
                        .currentIngestKeyId(ingest)
                        .currentCredentialGeneration(1)
                        .mediaRevision(1)
                        .build();
        ChannelStreamCredentialEntity cred =
                ChannelStreamCredentialEntity.builder()
                        .id(UUID.randomUUID())
                        .channelId(channelId)
                        .generation(1)
                        .ingestKeyId(ingest)
                        .status(CredentialStatus.ACTIVE)
                        .encryptedRelayReadJwt("cipher")
                        .build();
        StreamSessionLifecycleSupport support;
        StreamMediaOperationWorkerImpl worker;

        Fixture(MediaOperationType type) {
            PlatformTransactionManager tx =
                    new PlatformTransactionManager() {
                        public TransactionStatus getTransaction(TransactionDefinition d) {
                            return new SimpleTransactionStatus();
                        }

                        public void commit(TransactionStatus s) {}

                        public void rollback(TransactionStatus s) {}
                    };
            support =
                    new StreamSessionLifecycleSupport(
                            sessions,
                            mock(StreamStatusHistoryRepository.class),
                            ops,
                            media,
                            mock(StreamViewerPresenceService.class),
                            new ObjectMapper(),
                            clock);
            op =
                    StreamMediaOperationEntity.builder()
                            .channelId(channelId)
                            .credentialGeneration(1)
                            .mediaRevision(1)
                            .operationType(type)
                            .status(MediaOperationStatus.PENDING)
                            .targetIngestKeyId(ingest)
                            .targetSourceId(source)
                            .targetSourceType(MediaSourceType.RTMP_CONN)
                            .nextAttemptAt(LocalDateTime.now(clock))
                            .payload(
                                    support.encodePayload(
                                            new StreamSessionLifecycleSupport.OperationPayload(
                                                    LocalDateTime.now(clock),
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null)))
                            .build();
            when(ops.findById(op.getId())).thenAnswer(a -> Optional.of(op));
            when(ops.findChannelIdById(op.getId())).thenReturn(Optional.of(channelId));
            when(ops.findByIdForUpdate(op.getId())).thenAnswer(a -> Optional.of(op));
            when(ops.saveAndFlush(any())).thenAnswer(a -> a.getArgument(0));
            when(ops.findByChannelIdAndStatusIn(any(), any())).thenAnswer(a -> List.of(op));
            when(configs.findByIdForUpdate(channelId)).thenAnswer(a -> Optional.of(config));
            when(configs.saveAndFlush(any())).thenAnswer(a -> a.getArgument(0));
            when(creds.findByChannelIdAndGeneration(any(), anyLong()))
                    .thenAnswer(a -> Optional.of(cred));
            when(sessions.findSlotOccupyingByChannelIdForUpdate(channelId))
                    .thenAnswer(
                            a ->
                                    Optional.ofNullable(session.get())
                                            .filter(
                                                    value ->
                                                            value.getStatus()
                                                                    != StreamSessionStatus.ENDED));
            when(sessions.findByIdForUpdate(any()))
                    .thenAnswer(
                            a ->
                                    Optional.ofNullable(session.get())
                                            .filter(s -> s.getId().equals(a.getArgument(0))));
            when(sessions.saveAndFlush(any()))
                    .thenAnswer(
                            a -> {
                                StreamSessionEntity s = a.getArgument(0);
                                session.set(s);
                                return s;
                            });
            when(cipher.decrypt(any(), any(), any(), anyLong(), any())).thenReturn("relay");
            when(media.getIngestSource(ingest))
                    .thenAnswer(
                            a ->
                                    Optional.of(
                                            new MediaPathStatus(
                                                    "ingest/" + ingest,
                                                    true,
                                                    source,
                                                    MediaSourceType.RTMP_CONN,
                                                    clock.instant())));
            doAnswer(
                            a -> {
                                paths.add(a.getArgument(0));
                                return null;
                            })
                    .when(media)
                    .ensurePlaybackPath(any(), any(), any());
            doAnswer(
                            a -> {
                                paths.remove(a.getArgument(0));
                                return null;
                            })
                    .when(media)
                    .deletePlaybackPath(any());
            when(media.getPlaybackSource(any()))
                    .thenAnswer(
                            a ->
                                    paths.contains(a.getArgument(0))
                                            ? Optional.of(
                                                    new MediaPathStatus(
                                                            "live/" + a.getArgument(0),
                                                            true,
                                                            null,
                                                            MediaSourceType.RTMP_SOURCE,
                                                            clock.instant()))
                                            : Optional.empty());
            worker =
                    new StreamMediaOperationWorkerImpl(
                            ops,
                            configs,
                            creds,
                            sessions,
                            media,
                            cipher,
                            mock(StreamEligibilityService.class),
                            support,
                            tx,
                            null,
                            null);
        }

        StreamSessionEntity old(StreamSessionStatus status) {
            return StreamSessionEntity.builder()
                    .id(UUID.randomUUID())
                    .channelId(channelId)
                    .ownerUserId(config.getOwnerUserId())
                    .streamerId(config.getStreamerId())
                    .title("Old")
                    .categoryId(config.getCategoryId())
                    .visibility(StreamVisibility.PUBLIC)
                    .status(status)
                    .mediaPathState(MediaPathState.OPEN)
                    .ingestKeyId(ingest)
                    .mediaSourceId(UUID.randomUUID())
                    .mediaSourceType(MediaSourceType.RTMP_CONN)
                    .startedAt(LocalDateTime.now(clock).minusMinutes(20))
                    .build();
        }
    }

    @Test
    void unknownDisconnectCreatesNewSessionRatherThanMergingBroadcasts() {
        Fixture fixture = new Fixture(MediaOperationType.PROCESS_ONLINE);
        StreamSessionEntity old = fixture.old(StreamSessionStatus.LIVE);
        fixture.session.set(old);
        assertTrue(fixture.worker.executeSingleOperation(fixture.op.getId()));
        assertEquals(StreamSessionStatus.ENDED, old.getStatus());
        assertNotEquals(old.getId(), fixture.session.get().getId());
        assertEquals(MediaOperationStatus.SUCCEEDED, fixture.op.getStatus());
    }

    @Test
    void onlineAfterGraceUsesRevisionAfterEndingOldSession() {
        Fixture fixture = new Fixture(MediaOperationType.PROCESS_ONLINE);
        StreamSessionEntity old = fixture.old(StreamSessionStatus.RECONNECTING);
        old.setDisconnectedAt(LocalDateTime.now(fixture.clock).minusSeconds(120));
        old.setReconnectDeadlineAt(LocalDateTime.now(fixture.clock).minusSeconds(60));
        fixture.session.set(old);
        assertTrue(fixture.worker.executeSingleOperation(fixture.op.getId()));
        assertEquals(StreamSessionStatus.ENDED, old.getStatus());
        assertEquals(StreamSessionStatus.LIVE, fixture.session.get().getStatus());
        assertEquals(MediaOperationStatus.SUCCEEDED, fixture.op.getStatus());
        assertTrue(fixture.paths.contains(fixture.session.get().getId()));
    }

    @Test
    void leaseExpiryCannotReclaimAnExecutingMediaEffect() {
        Fixture fixture = new Fixture(MediaOperationType.PROCESS_ONLINE);
        doAnswer(
                        invocation -> {
                            fixture.paths.add(invocation.getArgument(0));
                            fixture.clock.t = fixture.clock.t.plusSeconds(61);
                            assertFalse(fixture.worker.executeSingleOperation(fixture.op.getId()));
                            return null;
                        })
                .when(fixture.media)
                .ensurePlaybackPath(any(), any(), any());
        assertTrue(fixture.worker.executeSingleOperation(fixture.op.getId()));
        assertEquals(MediaOperationStatus.SUCCEEDED, fixture.op.getStatus());
        assertTrue(fixture.paths.contains(fixture.session.get().getId()));
        verify(fixture.media, never()).deletePlaybackPath(fixture.session.get().getId());
    }

    @Test
    void workerAcquiresConfigBeforeOperationRowLock() {
        Fixture fixture = new Fixture(MediaOperationType.PROVISION_INGEST);
        fixture.worker.executeSingleOperation(fixture.op.getId());
        int configLock = mockingDetails(fixture.configs).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("findByIdForUpdate"))
                .mapToInt(invocation -> invocation.getSequenceNumber()).min().orElseThrow();
        int operationLock = mockingDetails(fixture.ops).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("findByIdForUpdate"))
                .mapToInt(invocation -> invocation.getSequenceNumber()).min().orElseThrow();
        assertTrue(configLock < operationLock);
    }

    @Test
    void reclaimedAttemptCannotCommitOrCleanUpSuccessfulPlayback() {
        Fixture fixture = new Fixture(MediaOperationType.PROCESS_ONLINE);
        doAnswer(
                        invocation -> {
                            UUID sessionId = invocation.getArgument(0);
                            fixture.paths.add(sessionId);
                            fixture.op.setStatus(MediaOperationStatus.SUCCEEDED);
                            fixture.op.setLeaseOwner("replacement-attempt");
                            return null;
                        })
                .when(fixture.media)
                .ensurePlaybackPath(any(), any(), any());
        fixture.worker.executeSingleOperation(fixture.op.getId());
        assertTrue(fixture.paths.contains(fixture.op.getCandidateSessionId()));
        verify(fixture.media, never()).deletePlaybackPath(any());
    }

    @Test
    void oldAttemptCannotScheduleRetryForAReplacementOwner() {
        Fixture fixture = new Fixture(MediaOperationType.PROCESS_ONLINE);
        doAnswer(invocation -> {
            fixture.op.setLeaseOwner("replacement-attempt");
            throw new IllegalStateException("Temporary media failure");
        }).when(fixture.media).ensurePlaybackPath(any(), any(), any());
        fixture.worker.executeSingleOperation(fixture.op.getId());
        assertEquals(MediaOperationStatus.RUNNING, fixture.op.getStatus());
        assertEquals("replacement-attempt", fixture.op.getLeaseOwner());
        assertEquals(0, fixture.op.getAttemptCount());
    }

    @Test
    void playbackCanBeRepairedAgainAfterAnEarlierRepairSucceeded() {

        Fixture h = new Fixture(MediaOperationType.PROCESS_ONLINE);
        StreamSessionEntity s = h.old(StreamSessionStatus.LIVE);
        s.setMediaSourceId(h.source);
        h.session.set(s);
        when(h.sessions.findByStatusIn(any())).thenAnswer(a -> List.of(s));
        when(h.sessions.findById(s.getId())).thenAnswer(a -> Optional.of(s));
        when(h.configs.findByStatusIn(any())).thenAnswer(a -> List.of(h.config));
        when(h.configs.findById(h.channelId)).thenAnswer(a -> Optional.of(h.config));
        when(h.creds.findByIngestKeyId(h.ingest)).thenAnswer(a -> Optional.of(h.cred));
        MediaPathConfiguration ingest =
                new MediaPathConfiguration("ingest/" + h.ingest, "publisher", false);
        when(h.media.listConfigurations(anyInt(), anyInt()))
                .thenReturn(new MediaPage<>(1, 1, List.of(ingest)));
        when(h.media.listPaths(anyInt(), anyInt()))
                .thenReturn(
                        new MediaPage<>(
                                1,
                                1,
                                List.of(
                                        new MediaPathStatus(
                                                "ingest/" + h.ingest,
                                                true,
                                                h.source,
                                                MediaSourceType.RTMP_CONN,
                                                h.clock.instant()))));
        when(h.media.getServerInfo()).thenReturn(new MediaServerInfo("1.21.2", "same-started"));
        StreamMediaRuntimeStateRepository runtime = mock(StreamMediaRuntimeStateRepository.class);
        when(runtime.findById(any()))
                .thenAnswer(
                        a ->
                                Optional.of(
                                        StreamMediaRuntimeStateEntity.builder()
                                                .serverStartedAt("same-started")
                                                .build()));
        AtomicReference<StreamMediaOperationEntity> enqueued = new AtomicReference<>();
        AtomicInteger count = new AtomicInteger();
        when(h.ops.findByChannelIdAndOperationTypeAndDeduplicationKey(any(), any(), any()))
                .thenAnswer(
                        a ->
                                Optional.ofNullable(enqueued.get())
                                        .filter(
                                                op ->
                                                        op.getOperationType() == a.getArgument(1)
                                                                && op.getDeduplicationKey()
                                                                        .equals(a.getArgument(2))));
        when(h.ops.saveAndFlush(any()))
                .thenAnswer(
                        a -> {
                            StreamMediaOperationEntity o = a.getArgument(0);
                            enqueued.set(o);
                            count.incrementAndGet();
                            return o;
                        });
        PlatformTransactionManager tx =
                new PlatformTransactionManager() {
                    public TransactionStatus getTransaction(TransactionDefinition d) {
                        return new SimpleTransactionStatus();
                    }

                    public void commit(TransactionStatus s) {}

                    public void rollback(TransactionStatus s) {}
                };
        StreamMediaReconcilerImpl reconciler =
                new StreamMediaReconcilerImpl(
                        h.media,
                        runtime,
                        h.configs,
                        h.creds,
                        h.sessions,
                        h.ops,
                        mock(StreamMediaOperationWorker.class),
                        h.support,
                        tx,
                        null);

        reconciler.reconcileOnce();
        assertEquals(1, count.get());
        enqueued.get().setStatus(MediaOperationStatus.SUCCEEDED);
        reconciler.reconcileOnce();
        assertEquals(2, count.get());
    }

    @Test
    void unfinishedRestartSweepRetainsWatermarkAndRetries() {

        Fixture h = new Fixture(MediaOperationType.PROCESS_ONLINE);
        StreamSessionEntity s = h.old(StreamSessionStatus.LIVE);
        s.setMediaSourceId(h.source);
        h.session.set(s);
        when(h.sessions.findByStatusIn(any())).thenAnswer(a -> List.of(s));
        when(h.sessions.findById(s.getId())).thenAnswer(a -> Optional.of(s));
        when(h.configs.findByStatusIn(any())).thenAnswer(a -> List.of(h.config));
        when(h.configs.findById(h.channelId)).thenAnswer(a -> Optional.of(h.config));
        when(h.creds.findByIngestKeyId(h.ingest)).thenAnswer(a -> Optional.of(h.cred));
        MediaPathConfiguration ingest =
                new MediaPathConfiguration("ingest/" + h.ingest, "publisher", false);
        when(h.media.listConfigurations(anyInt(), anyInt()))
                .thenReturn(new MediaPage<>(1, 1, List.of(ingest)));
        when(h.media.listPaths(anyInt(), anyInt()))
                .thenReturn(
                        new MediaPage<>(
                                1,
                                1,
                                List.of(
                                        new MediaPathStatus(
                                                "ingest/" + h.ingest,
                                                true,
                                                h.source,
                                                MediaSourceType.RTMP_CONN,
                                                h.clock.instant()))));
        when(h.media.getServerInfo()).thenReturn(new MediaServerInfo("1.21.2", "same-started"));
        StreamMediaRuntimeStateRepository runtime = mock(StreamMediaRuntimeStateRepository.class);
        when(runtime.findById(any()))
                .thenAnswer(
                        a ->
                                Optional.of(
                                        StreamMediaRuntimeStateEntity.builder()
                                                .serverStartedAt("same-started")
                                                .build()));
        AtomicReference<StreamMediaOperationEntity> enqueued = new AtomicReference<>();
        AtomicInteger count = new AtomicInteger();
        when(h.ops.findByChannelIdAndOperationTypeAndDeduplicationKey(any(), any(), any()))
                .thenAnswer(
                        a ->
                                Optional.ofNullable(enqueued.get())
                                        .filter(
                                                op ->
                                                        op.getOperationType() == a.getArgument(1)
                                                                && op.getDeduplicationKey()
                                                                        .equals(a.getArgument(2))));
        when(h.ops.saveAndFlush(any()))
                .thenAnswer(
                        a -> {
                            StreamMediaOperationEntity o = a.getArgument(0);
                            enqueued.set(o);
                            count.incrementAndGet();
                            return o;
                        });
        PlatformTransactionManager tx =
                new PlatformTransactionManager() {
                    public TransactionStatus getTransaction(TransactionDefinition d) {
                        return new SimpleTransactionStatus();
                    }

                    public void commit(TransactionStatus s) {}

                    public void rollback(TransactionStatus s) {}
                };
        StreamMediaReconcilerImpl reconciler =
                new StreamMediaReconcilerImpl(
                        h.media,
                        runtime,
                        h.configs,
                        h.creds,
                        h.sessions,
                        h.ops,
                        mock(StreamMediaOperationWorker.class),
                        h.support,
                        tx,
                        null);

        StreamMediaRuntimeStateEntity state =
                StreamMediaRuntimeStateEntity.builder().serverStartedAt("old-started").build();
        when(runtime.findById(any())).thenReturn(Optional.of(state));
        when(runtime.findByIdForUpdate(any())).thenReturn(Optional.of(state));
        when(h.sessions.findChannelIdById(s.getId())).thenReturn(Optional.of(h.channelId));
        when(h.sessions.findByStatusIn(any()))
                .thenThrow(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "temporary failure"))
                .thenReturn(List.of(s));
        reconciler.reconcileOnce();
        assertEquals("old-started", state.getServerStartedAt());
        assertFalse(s.isEndRequested());
        reconciler.reconcileOnce();
        assertTrue(s.isEndRequested());
        assertEquals("same-started", state.getServerStartedAt());
    }
}
