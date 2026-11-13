package com.reallive.streamservice.stream.repository;

import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.entity.ChannelStreamConfigEntity;
import com.reallive.streamservice.stream.entity.ChannelStreamCredentialEntity;
import com.reallive.streamservice.stream.entity.StreamMediaOperationEntity;
import com.reallive.streamservice.stream.entity.StreamMediaRuntimeStateEntity;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.StreamStatusHistoryEntity;
import com.reallive.streamservice.stream.entity.enums.ChannelStreamConfigStatus;
import com.reallive.streamservice.stream.entity.enums.CredentialStatus;
import com.reallive.streamservice.stream.entity.enums.MediaOperationStatus;
import com.reallive.streamservice.stream.entity.enums.MediaOperationType;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.entity.enums.ThumbnailJobStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=validate")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StreamRepositoryPostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        configureDatabase(registry, SCHEMA);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        dropSchema(SCHEMA);
    }

    @Autowired
    private StreamSessionRepository sessionRepository;

    @Autowired
    private StreamStatusHistoryRepository historyRepository;

    @Autowired
    private ChannelStreamConfigRepository configRepository;

    @Autowired
    private ChannelStreamCredentialRepository credentialRepository;

    @Autowired
    private StreamMediaOperationRepository operationRepository;

    @Autowired
    private StreamMediaRuntimeStateRepository runtimeStateRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void persistsSessionWithAllEnumsAndRoundsTripSuccessfully() {
        UUID ownerId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID ingestKeyId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        configRepository.saveAndFlush(ChannelStreamConfigEntity.builder()
                .channelId(channelId)
                .streamerId(streamerId)
                .ownerUserId(ownerId)
                .title("Config Title")
                .categoryId(categoryId)
                .visibility(StreamVisibility.PUBLIC)
                .status(ChannelStreamConfigStatus.ACTIVE)
                .currentIngestKeyId(ingestKeyId)
                .currentCredentialGeneration(1L)
                .mediaRevision(1L)
                .build());

        credentialRepository.saveAndFlush(ChannelStreamCredentialEntity.builder()
                .channelId(channelId)
                .generation(1L)
                .ingestKeyId(ingestKeyId)
                .status(CredentialStatus.ACTIVE)
                .encryptedPublishJwt("v1:enc:pub")
                .encryptedRelayReadJwt("v1:enc:rel")
                .issuedAt(now)
                .activatedAt(now)
                .build());

        StreamSessionEntity session = StreamSessionEntity.builder()
                .channelId(channelId)
                .streamerId(streamerId)
                .ownerUserId(ownerId)
                .categoryId(categoryId)
                .title("Repository Roundtrip Stream")
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.LIVE)
                .playbackPath("/live/" + UUID.randomUUID() + "/index.m3u8")
                .ingestKeyId(ingestKeyId)
                .endRequested(false)
                .mediaPathState(MediaPathState.OPEN)
                .mediaSourceId(UUID.randomUUID())
                .mediaSourceType(MediaSourceType.RTMP_CONN)
                .startedAt(now)
                .build();

        StreamSessionEntity saved = sessionRepository.save(session);
        assertNotNull(saved.getId());

        StreamSessionEntity reloaded = sessionRepository.findById(saved.getId()).orElseThrow();
        assertEquals(saved.getId(), reloaded.getId());
        assertEquals(channelId, reloaded.getChannelId());
        assertEquals(ownerId, reloaded.getOwnerUserId());
        assertEquals(ingestKeyId, reloaded.getIngestKeyId());
        assertNull(reloaded.getCreationRequestId());
        assertEquals("Repository Roundtrip Stream", reloaded.getTitle());
        assertEquals(StreamVisibility.PUBLIC, reloaded.getVisibility());
        assertEquals(StreamSessionStatus.LIVE, reloaded.getStatus());
        assertEquals(MediaPathState.OPEN, reloaded.getMediaPathState());
        assertEquals(MediaSourceType.RTMP_CONN, reloaded.getMediaSourceType());
        assertEquals(saved.getMediaSourceId(), reloaded.getMediaSourceId());
        assertNotNull(reloaded.getCreatedAt());
        assertNotNull(reloaded.getUpdatedAt());
    }

    @ParameterizedTest
    @EnumSource(ThumbnailJobStatus.class)
    void persistsAndReloadsThumbnailImageAndEveryJobStatus(ThumbnailJobStatus status) {
        LocalDateTime updatedAt = LocalDateTime.of(2026, 11, 5, 1, 2, 3);
        LocalDateTime deadline = updatedAt.plusMinutes(4);
        UUID jobId = UUID.randomUUID();
        String objectKey = "livestream-thumbnails/" + UUID.randomUUID() + "/" + jobId + ".jpg";

        StreamSessionEntity session = sessionRepository.saveAndFlush(StreamSessionEntity.builder()
                .channelId(UUID.randomUUID())
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .title("Thumbnail Roundtrip Stream")
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.LIVE)
                .playbackPath("/live/" + UUID.randomUUID() + "/index.m3u8")
                .mediaPathState(MediaPathState.OPEN)
                .mediaSourceId(UUID.randomUUID())
                .mediaSourceType(MediaSourceType.RTMP_CONN)
                .startedAt(updatedAt.minusMinutes(10))
                .thumbnailUrl("https://cdn.example.com/" + objectKey)
                .thumbnailObjectKey(objectKey)
                .thumbnailUpdatedAt(updatedAt)
                .thumbnailJobId(jobId)
                .thumbnailJobStatus(status)
                .thumbnailJobDeadlineAt(deadline)
                .build());

        StreamSessionEntity reloaded = sessionRepository.findById(session.getId()).orElseThrow();

        assertEquals("https://cdn.example.com/" + objectKey, reloaded.getThumbnailUrl());
        assertEquals(objectKey, reloaded.getThumbnailObjectKey());
        assertEquals(updatedAt, reloaded.getThumbnailUpdatedAt());
        assertEquals(jobId, reloaded.getThumbnailJobId());
        assertEquals(status, reloaded.getThumbnailJobStatus());
        assertEquals(deadline, reloaded.getThumbnailJobDeadlineAt());
    }

    @Test
    void keepsThumbnailStateNullWhenNoJobHasBeenDispatched() {
        StreamSessionEntity session = sessionRepository.saveAndFlush(StreamSessionEntity.builder()
                .channelId(UUID.randomUUID())
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .title("No Thumbnail Yet")
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.LIVE)
                .playbackPath("/live/" + UUID.randomUUID() + "/index.m3u8")
                .mediaPathState(MediaPathState.OPEN)
                .mediaSourceId(UUID.randomUUID())
                .mediaSourceType(MediaSourceType.RTMP_CONN)
                .startedAt(LocalDateTime.of(2026, 11, 5, 1, 0))
                .build());

        StreamSessionEntity reloaded = sessionRepository.findById(session.getId()).orElseThrow();

        assertNull(reloaded.getThumbnailUrl());
        assertNull(reloaded.getThumbnailObjectKey());
        assertNull(reloaded.getThumbnailUpdatedAt());
        assertNull(reloaded.getThumbnailJobId());
        assertNull(reloaded.getThumbnailJobStatus());
        assertNull(reloaded.getThumbnailJobDeadlineAt());
    }

    @Test
    void slotQueryIdentifiesOccupiedChannelAndIgnoresClosedEndedSession() {
        UUID channelId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        StreamSessionEntity session = StreamSessionEntity.builder()
                .channelId(channelId)
                .streamerId(UUID.randomUUID())
                .ownerUserId(ownerId)
                .categoryId(UUID.randomUUID())
                .title("Active Slot Stream")
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.LIVE)
                .playbackPath("/live/" + UUID.randomUUID() + "/index.m3u8")
                .mediaPathState(MediaPathState.OPEN)
                .mediaSourceId(UUID.randomUUID())
                .mediaSourceType(MediaSourceType.RTMP_CONN)
                .startedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();

        session = sessionRepository.save(session);

        assertTrue(sessionRepository.findSlotOccupyingByChannelId(channelId).isPresent());

        final UUID lockedChannelId = channelId;
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertTrue(sessionRepository.findSlotOccupyingByChannelIdForUpdate(lockedChannelId).isPresent()));

        session.setStatus(StreamSessionStatus.ENDED);
        session.setEndedAt(LocalDateTime.now(ZoneOffset.UTC));
        session.setMediaPathState(MediaPathState.CLOSED);
        sessionRepository.save(session);

        assertFalse(sessionRepository.findSlotOccupyingByChannelId(channelId).isPresent());
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertFalse(sessionRepository.findSlotOccupyingByChannelIdForUpdate(lockedChannelId).isPresent()));
    }

    @Test
    void findsExpiredReconnectingSessionsByDeadline() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        StreamSessionEntity expiredSession = StreamSessionEntity.builder()
                .channelId(UUID.randomUUID())
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .title("Expired Reconnect")
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.RECONNECTING)
                .playbackPath("/live/" + UUID.randomUUID() + "/index.m3u8")
                .mediaPathState(MediaPathState.OPEN)
                .startedAt(now.minusMinutes(5))
                .disconnectedAt(now.minusSeconds(70))
                .reconnectDeadlineAt(now.minusSeconds(10))
                .build();
        expiredSession = sessionRepository.save(expiredSession);

        StreamSessionEntity pendingSession = StreamSessionEntity.builder()
                .channelId(UUID.randomUUID())
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .title("Pending Reconnect")
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.RECONNECTING)
                .playbackPath("/live/" + UUID.randomUUID() + "/index.m3u8")
                .mediaPathState(MediaPathState.OPEN)
                .startedAt(now.minusMinutes(5))
                .disconnectedAt(now.minusSeconds(10))
                .reconnectDeadlineAt(now.plusSeconds(50))
                .build();
        sessionRepository.save(pendingSession);

        List<UUID> expiredIds = sessionRepository.findExpiredReconnectingIds(now, PageRequest.of(0, 10));
        assertTrue(expiredIds.contains(expiredSession.getId()));
        assertFalse(expiredIds.contains(pendingSession.getId()));
    }

    @Test
    void paginatesPublicLiveStreamsWithNewestStartedFirst() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        StreamSessionEntity earlier = sessionRepository.save(StreamSessionEntity.builder()
                .channelId(UUID.randomUUID())
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .title("Earlier Stream")
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.LIVE)
                .playbackPath("/live/" + UUID.randomUUID() + "/index.m3u8")
                .mediaPathState(MediaPathState.OPEN)
                .mediaSourceId(UUID.randomUUID())
                .mediaSourceType(MediaSourceType.RTMP_CONN)
                .startedAt(now.minusMinutes(30))
                .build());

        StreamSessionEntity later = sessionRepository.save(StreamSessionEntity.builder()
                .channelId(UUID.randomUUID())
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .title("Later Stream")
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.LIVE)
                .playbackPath("/live/" + UUID.randomUUID() + "/index.m3u8")
                .mediaPathState(MediaPathState.OPEN)
                .mediaSourceId(UUID.randomUUID())
                .mediaSourceType(MediaSourceType.RTMP_CONN)
                .startedAt(now.minusMinutes(5))
                .build());

        Page<StreamSessionEntity> page = sessionRepository.findByVisibilityAndStatusOrderByStartedAtDescIdAsc(
                StreamVisibility.PUBLIC, StreamSessionStatus.LIVE, PageRequest.of(0, 10)
        );

        assertTrue(page.getTotalElements() >= 2);
        List<UUID> ids = page.getContent().stream().map(StreamSessionEntity::getId).toList();
        int laterIdx = ids.indexOf(later.getId());
        int earlierIdx = ids.indexOf(earlier.getId());
        assertTrue(laterIdx < earlierIdx, "Newer started stream must appear before earlier stream");
    }

    @Test
    void discoveryFiltersStatusAndSessionCategoryWithoutVisibilityRestriction() {
        UUID category = UUID.randomUUID();
        var live = discoverySession(category, StreamSessionStatus.LIVE, StreamVisibility.PRIVATE);
        var reconnecting = discoverySession(category, StreamSessionStatus.RECONNECTING, StreamVisibility.PUBLIC);
        var other = discoverySession(UUID.randomUUID(), StreamSessionStatus.LIVE, StreamVisibility.PUBLIC);
        sessionRepository.saveAll(List.of(live, reconnecting, other));

        var page = sessionRepository.findByStatusAndCategoryId(StreamSessionStatus.LIVE, category, PageRequest.of(0, 1));
        assertEquals(1, page.getTotalElements());
        assertEquals(live.getId(), page.getContent().getFirst().getId());
        assertTrue(sessionRepository.findByStatusAndCategoryId(StreamSessionStatus.LIVE, category, PageRequest.of(1, 1)).isEmpty());
    }

    @Test
    void persistsDueMediaOperationsAndRuntimeStateAcrossRestartCheck() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 2, 0, 0);
        UUID channelId = UUID.randomUUID();
        configRepository.saveAndFlush(ChannelStreamConfigEntity.builder()
                .channelId(channelId)
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .title("")
                .visibility(StreamVisibility.PUBLIC)
                .status(ChannelStreamConfigStatus.DRAFT)
                .build());

        StreamMediaOperationEntity duePending = operationRepository.saveAndFlush(StreamMediaOperationEntity.builder()
                .channelId(channelId)
                .credentialGeneration(0L)
                .mediaRevision(0L)
                .operationType(MediaOperationType.PROVISION_INGEST)
                .status(MediaOperationStatus.PENDING)
                .nextAttemptAt(now.minusSeconds(1))
                .build());
        StreamMediaOperationEntity expiredLease = operationRepository.saveAndFlush(StreamMediaOperationEntity.builder()
                .channelId(channelId)
                .credentialGeneration(0L)
                .mediaRevision(0L)
                .operationType(MediaOperationType.PROCESS_ONLINE)
                .status(MediaOperationStatus.RUNNING)
                .nextAttemptAt(now.minusSeconds(10))
                .leaseOwner("dead-worker")
                .leaseUntil(now.minusSeconds(1))
                .build());
        StreamMediaOperationEntity futurePending = operationRepository.saveAndFlush(StreamMediaOperationEntity.builder()
                .channelId(channelId)
                .credentialGeneration(0L)
                .mediaRevision(0L)
                .operationType(MediaOperationType.PROCESS_OFFLINE)
                .status(MediaOperationStatus.PENDING)
                .nextAttemptAt(now.plusSeconds(30))
                .build());

        List<UUID> dueIds = operationRepository.findDueOperationIds(now, PageRequest.of(0, 10));
        assertTrue(dueIds.contains(duePending.getId()));
        assertTrue(dueIds.contains(expiredLease.getId()));
        assertFalse(dueIds.contains(futurePending.getId()));

        runtimeStateRepository.saveAndFlush(StreamMediaRuntimeStateEntity.builder()
                .id(StreamMediaRuntimeStateEntity.PRIMARY_ID)
                .serverStartedAt("2026-11-05T02:00:00Z")
                .lastReconciledAt(now)
                .updatedAt(now)
                .build());
        assertEquals("2026-11-05T02:00:00Z", runtimeStateRepository.findById(StreamMediaRuntimeStateEntity.PRIMARY_ID)
                .orElseThrow().getServerStartedAt());
    }

    @Test
    void persistsImmutableHistoryWithEventDeduplication() {
        UUID ownerId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 0, 0, 0, 123_456_000);

        StreamSessionEntity session = sessionRepository.save(StreamSessionEntity.builder()
                .channelId(channelId)
                .streamerId(UUID.randomUUID())
                .ownerUserId(ownerId)
                .categoryId(UUID.randomUUID())
                .title("Session With History")
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.LIVE)
                .playbackPath("/live/" + UUID.randomUUID() + "/index.m3u8")
                .mediaPathState(MediaPathState.OPEN)
                .mediaSourceId(UUID.randomUUID())
                .mediaSourceType(MediaSourceType.RTMP_CONN)
                .startedAt(now.minusMinutes(1))
                .build());

        UUID streamId = session.getId();
        UUID eventId = UUID.randomUUID();

        StreamStatusHistoryEntity history = StreamStatusHistoryEntity.builder()
                .streamId(streamId)
                .previousStatus(null)
                .status(StreamSessionStatus.LIVE)
                .reason("First stream start")
                .mediaSourceId(session.getMediaSourceId())
                .mediaEventId(eventId)
                .createdAt(now)
                .build();
        historyRepository.save(history);

        assertTrue(historyRepository.existsByMediaEventId(eventId));
        assertEquals(1, historyRepository.findByStreamIdOrderByCreatedAtAscIdAsc(streamId).size());
        assertEquals(now, historyRepository.findByMediaEventId(eventId).orElseThrow().getCreatedAt());
    }

    private StreamSessionEntity discoverySession(UUID category, StreamSessionStatus status, StreamVisibility visibility) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return StreamSessionEntity.builder()
                .channelId(UUID.randomUUID())
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .categoryId(category)
                .title("Discovery")
                .visibility(visibility)
                .status(status)
                .playbackPath("/live/" + UUID.randomUUID() + "/index.m3u8")
                .mediaPathState(MediaPathState.OPEN)
                .mediaSourceId(status == StreamSessionStatus.LIVE ? UUID.randomUUID() : null)
                .mediaSourceType(status == StreamSessionStatus.LIVE ? MediaSourceType.RTMP_CONN : null)
                .startedAt(now.minusMinutes(5))
                .disconnectedAt(status == StreamSessionStatus.RECONNECTING ? now.minusSeconds(10) : null)
                .reconnectDeadlineAt(status == StreamSessionStatus.RECONNECTING ? now.plusSeconds(50) : null)
                .build();
    }
}
