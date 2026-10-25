package com.reallive.streamservice.stream.repository;

import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.StreamStatusHistoryEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private PlatformTransactionManager transactionManager;

    @Test
    void persistsSessionWithAllEnumsAndRoundsTripSuccessfully() {
        UUID ownerId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        StreamSessionEntity session = StreamSessionEntity.builder()
                .channelId(channelId)
                .streamerId(UUID.randomUUID())
                .ownerUserId(ownerId)
                .categoryId(UUID.randomUUID())
                .title("Repository Roundtrip Stream")
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.LIVE)
                .playbackPath("/live/" + UUID.randomUUID() + "/index.m3u8")
                .creationRequestId(requestId)
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
        assertEquals("Repository Roundtrip Stream", reloaded.getTitle());
        assertEquals(StreamVisibility.PUBLIC, reloaded.getVisibility());
        assertEquals(StreamSessionStatus.LIVE, reloaded.getStatus());
        assertEquals(MediaPathState.OPEN, reloaded.getMediaPathState());
        assertEquals(MediaSourceType.RTMP_CONN, reloaded.getMediaSourceType());
        assertEquals(saved.getMediaSourceId(), reloaded.getMediaSourceId());
        assertNotNull(reloaded.getCreatedAt());
        assertNotNull(reloaded.getUpdatedAt());

        var byRequest = sessionRepository.findByOwnerUserIdAndCreationRequestId(ownerId, requestId);
        assertTrue(byRequest.isPresent());
        assertEquals(saved.getId(), byRequest.get().getId());
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
                .status(StreamSessionStatus.READY)
                .playbackPath("/live/" + UUID.randomUUID() + "/index.m3u8")
                .creationRequestId(UUID.randomUUID())
                .mediaPathState(MediaPathState.OPEN)
                .build();

        session = sessionRepository.save(session);

        assertTrue(sessionRepository.findSlotOccupyingByChannelId(channelId).isPresent());

        final UUID lockedChannelId = channelId;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertTrue(sessionRepository.findSlotOccupyingByChannelIdForUpdate(lockedChannelId).isPresent());
        });

        // Update to ENDED and CLOSED -> slot is no longer occupied
        session.setStatus(StreamSessionStatus.ENDED);
        session.setEndedAt(LocalDateTime.now(ZoneOffset.UTC));
        session.setMediaPathState(MediaPathState.CLOSED);
        sessionRepository.save(session);

        assertFalse(sessionRepository.findSlotOccupyingByChannelId(channelId).isPresent());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertFalse(sessionRepository.findSlotOccupyingByChannelIdForUpdate(lockedChannelId).isPresent());
        });
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
                .creationRequestId(UUID.randomUUID())
                .mediaPathState(MediaPathState.OPEN)
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
                .creationRequestId(UUID.randomUUID())
                .mediaPathState(MediaPathState.OPEN)
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
                .creationRequestId(UUID.randomUUID())
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
                .creationRequestId(UUID.randomUUID())
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
    void persistsImmutableHistoryWithEventDeduplication() {
        UUID ownerId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        StreamSessionEntity session = sessionRepository.save(StreamSessionEntity.builder()
                .channelId(channelId)
                .streamerId(UUID.randomUUID())
                .ownerUserId(ownerId)
                .categoryId(UUID.randomUUID())
                .title("Session With History And Task")
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.READY)
                .playbackPath("/live/" + UUID.randomUUID() + "/index.m3u8")
                .creationRequestId(UUID.randomUUID())
                .mediaPathState(MediaPathState.OPEN)
                .build());

        UUID streamId = session.getId();
        UUID eventId = UUID.randomUUID();

        StreamStatusHistoryEntity history = StreamStatusHistoryEntity.builder()
                .streamId(streamId)
                .previousStatus(StreamSessionStatus.READY)
                .status(StreamSessionStatus.LIVE)
                .reason("First stream start")
                .mediaSourceId(UUID.randomUUID())
                .mediaEventId(eventId)
                .createdAt(now)
                .build();
        historyRepository.save(history);

        assertTrue(historyRepository.existsByMediaEventId(eventId));
        assertEquals(1, historyRepository.findByStreamIdOrderByCreatedAtAscIdAsc(streamId).size());

        assertEquals(now, historyRepository.findByMediaEventId(eventId).orElseThrow().getCreatedAt());
    }
}
