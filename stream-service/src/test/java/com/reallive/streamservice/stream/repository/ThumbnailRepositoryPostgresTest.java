package com.reallive.streamservice.stream.repository;

import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.entity.enums.ThumbnailJobStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=validate")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ThumbnailRepositoryPostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0, 0);
    private static final LocalDateTime CUTOFF = NOW.minusSeconds(60);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        configureDatabase(registry, SCHEMA);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        dropSchema(SCHEMA);
    }

    @Autowired
    private StreamSessionRepository sessions;

    @BeforeEach
    void clean() {
        sessions.deleteAll();
    }

    @Test
    void selectsDueLiveSessionsInStableOrderWithExplicitNullAndCutoffHandling() {
        StreamSessionEntity neverHadImage = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                null, null, null, null);
        StreamSessionEntity olderImage = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                CUTOFF.minusSeconds(10), ThumbnailJobStatus.SUCCEEDED, UUID.randomUUID(), NOW.plusSeconds(600));
        StreamSessionEntity exactlyAtCutoff = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                CUTOFF, ThumbnailJobStatus.SUCCEEDED, UUID.randomUUID(), NOW.plusSeconds(600));
        StreamSessionEntity recentImage = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                CUTOFF.plusSeconds(1), ThumbnailJobStatus.SUCCEEDED, UUID.randomUUID(), NOW.plusSeconds(600));
        StreamSessionEntity pending = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                null, ThumbnailJobStatus.PENDING_DISPATCH, UUID.randomUUID(), NOW.plusSeconds(60));
        StreamSessionEntity accepted = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                CUTOFF.minusSeconds(10), ThumbnailJobStatus.ACCEPTED, UUID.randomUUID(), NOW.plusSeconds(60));
        StreamSessionEntity reconnecting = save(StreamSessionStatus.RECONNECTING, MediaPathState.OPEN,
                null, null, null, null);
        StreamSessionEntity ended = save(StreamSessionStatus.ENDED, MediaPathState.CLOSED,
                null, null, null, null);

        List<UUID> due = sessions.findDueThumbnailSessionIds(CUTOFF, PageRequest.of(0, 10));

        assertEquals(List.of(neverHadImage.getId(), olderImage.getId(), exactlyAtCutoff.getId()), due);
        assertFalse(due.contains(recentImage.getId()));
        assertFalse(due.contains(pending.getId()));
        assertFalse(due.contains(accepted.getId()));
        assertFalse(due.contains(reconnecting.getId()));
        assertFalse(due.contains(ended.getId()));
        assertEquals(List.of(neverHadImage.getId()), sessions.findDueThumbnailSessionIds(CUTOFF, PageRequest.of(0, 1)));
    }

    @Test
    void selectsOnlyLivePendingJobsWithFutureDeadlineAndPreservesDeadlineOrder() {
        StreamSessionEntity first = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                null, ThumbnailJobStatus.PENDING_DISPATCH, UUID.randomUUID(), NOW.plusSeconds(10));
        StreamSessionEntity second = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                null, ThumbnailJobStatus.PENDING_DISPATCH, UUID.randomUUID(), NOW.plusSeconds(20));
        StreamSessionEntity expiresNow = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                null, ThumbnailJobStatus.PENDING_DISPATCH, UUID.randomUUID(), NOW);
        StreamSessionEntity expiresBeforeNow = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                null, ThumbnailJobStatus.PENDING_DISPATCH, UUID.randomUUID(), NOW.minusSeconds(1));
        StreamSessionEntity reconnecting = save(StreamSessionStatus.RECONNECTING, MediaPathState.OPEN,
                null, ThumbnailJobStatus.PENDING_DISPATCH, UUID.randomUUID(), NOW.plusSeconds(30));
        StreamSessionEntity ended = save(StreamSessionStatus.ENDED, MediaPathState.CLOSED,
                null, ThumbnailJobStatus.PENDING_DISPATCH, UUID.randomUUID(), NOW.plusSeconds(30));
        StreamSessionEntity accepted = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                null, ThumbnailJobStatus.ACCEPTED, UUID.randomUUID(), NOW.plusSeconds(30));
        StreamSessionEntity succeeded = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                null, ThumbnailJobStatus.SUCCEEDED, UUID.randomUUID(), NOW.plusSeconds(30));

        List<UUID> pending = sessions.findPendingThumbnailDispatchIds(NOW, PageRequest.of(0, 10));

        assertEquals(List.of(first.getId(), second.getId()), pending);
        assertFalse(pending.contains(expiresNow.getId()));
        assertFalse(pending.contains(expiresBeforeNow.getId()));
        assertFalse(pending.contains(reconnecting.getId()));
        assertFalse(pending.contains(ended.getId()));
        assertFalse(pending.contains(accepted.getId()));
        assertFalse(pending.contains(succeeded.getId()));
        assertEquals(List.of(first.getId()),
                sessions.findPendingThumbnailDispatchIds(NOW, PageRequest.of(0, 1)));
    }

    @Test
    void selectsOnlyExpiredWaitingJobsAndIgnoresCompletedOrFutureJobs() {
        StreamSessionEntity acceptedExpired = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                null, ThumbnailJobStatus.ACCEPTED, UUID.randomUUID(), NOW.minusSeconds(10));
        StreamSessionEntity pendingExpired = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                null, ThumbnailJobStatus.PENDING_DISPATCH, UUID.randomUUID(), NOW);
        StreamSessionEntity pendingFuture = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                null, ThumbnailJobStatus.PENDING_DISPATCH, UUID.randomUUID(), NOW.plusSeconds(10));
        StreamSessionEntity succeededExpired = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                null, ThumbnailJobStatus.SUCCEEDED, UUID.randomUUID(), NOW.minusSeconds(10));
        StreamSessionEntity failedExpired = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                null, ThumbnailJobStatus.FAILED, UUID.randomUUID(), NOW.minusSeconds(10));
        StreamSessionEntity noJob = save(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                null, null, null, null);

        List<UUID> expired = sessions.findExpiredThumbnailJobIds(NOW, PageRequest.of(0, 10));

        assertEquals(List.of(acceptedExpired.getId(), pendingExpired.getId()), expired);
        assertFalse(expired.contains(pendingFuture.getId()));
        assertFalse(expired.contains(succeededExpired.getId()));
        assertFalse(expired.contains(failedExpired.getId()));
        assertFalse(expired.contains(noJob.getId()));
        assertEquals(List.of(acceptedExpired.getId()),
                sessions.findExpiredThumbnailJobIds(NOW, PageRequest.of(0, 1)));
    }

    private StreamSessionEntity save(
            StreamSessionStatus status,
            MediaPathState mediaPathState,
            LocalDateTime thumbnailUpdatedAt,
            ThumbnailJobStatus jobStatus,
            UUID jobId,
            LocalDateTime jobDeadlineAt
    ) {
        boolean hasJob = jobStatus != null;
        UUID sessionId = UUID.randomUUID();
        return sessions.saveAndFlush(StreamSessionEntity.builder()
                .id(sessionId)
                .channelId(UUID.randomUUID())
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .title("Thumbnail query " + status)
                .visibility(StreamVisibility.PUBLIC)
                .status(status)
                .playbackPath("/live/" + sessionId + "/index.m3u8")
                .creationRequestId(UUID.randomUUID())
                .mediaPathState(mediaPathState)
                .mediaSourceId(UUID.randomUUID())
                .mediaSourceType(MediaSourceType.RTMP_CONN)
                .disconnectedAt(status == StreamSessionStatus.RECONNECTING ? NOW.minusSeconds(10) : null)
                .reconnectDeadlineAt(status == StreamSessionStatus.RECONNECTING ? NOW.plusSeconds(50) : null)
                .startedAt(NOW.minusMinutes(10))
                .endedAt(status == StreamSessionStatus.ENDED ? NOW : null)
                .thumbnailUpdatedAt(thumbnailUpdatedAt)
                .thumbnailJobId(hasJob ? jobId : null)
                .thumbnailJobStatus(jobStatus)
                .thumbnailJobDeadlineAt(hasJob ? jobDeadlineAt : null)
                .build());
    }
}
