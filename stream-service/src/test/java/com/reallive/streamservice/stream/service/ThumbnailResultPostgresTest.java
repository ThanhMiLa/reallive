package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.configuration.ThumbnailProperties;
import com.reallive.streamservice.stream.dto.request.ThumbnailResultRequest;
import com.reallive.streamservice.stream.dto.response.ThumbnailResultOutcome;
import com.reallive.streamservice.stream.dto.response.ThumbnailResultResponse;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.entity.enums.ThumbnailJobStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class ThumbnailResultPostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final Instant BASE = Instant.parse("2026-11-06T12:00:00Z");
    private static final String BASE_URL = "https://thumbnails.example.com/base/";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        configureDatabase(registry, SCHEMA);
        registry.add("stream.thumbnail.enabled", () -> "false");
        registry.add("stream.thumbnail.callback-enabled", () -> "true");
        registry.add("stream.thumbnail.public-base-url", () -> BASE_URL);
        registry.add("stream.thumbnail.max-jpeg-bytes", () -> "5242880");
    }

    @AfterAll
    static void cleanup() throws SQLException {
        dropSchema(SCHEMA);
    }

    @Autowired
    private ThumbnailResultService service;

    @Autowired
    private ThumbnailDispatchService dispatchService;

    @Autowired
    private StreamSessionRepository sessions;

    @Autowired
    private ThumbnailProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ControlledClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        jdbc.execute("DELETE FROM stream_sessions");
        clock.instant = BASE;
    }

    @Test
    void appliesPendingJobAndPersistsAllThumbnailFieldsTogether() {
        UUID jobId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        LocalDateTime deadline = localBase().plusSeconds(120);
        saveSession(sessionId, StreamSessionStatus.LIVE, null,
                ThumbnailJobStatus.PENDING_DISPATCH, jobId, deadline, null, null);

        ThumbnailResultResponse response = service.apply(request(jobId, sessionId));

        assertEquals(ThumbnailResultOutcome.APPLIED, response.outcome());
        Stored stored = stored(sessionId);
        assertEquals(BASE_URL + objectKey(sessionId, jobId), stored.url());
        assertEquals(objectKey(sessionId, jobId), stored.objectKey());
        assertEquals(localBase(), stored.updatedAt());
        assertEquals("SUCCEEDED", stored.jobStatus());
        assertEquals(jobId, stored.jobId());
        assertEquals(deadline, stored.deadline());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Ho_Chi_Minh"})
    void persistsUtcThumbnailTimesAndQueriesDueJobsIndependentlyOfJvmTimezone(String zone) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            UUID jobId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            LocalDateTime deadline = localBase().plusSeconds(120);
            saveSession(sessionId, StreamSessionStatus.LIVE, null,
                    ThumbnailJobStatus.ACCEPTED, jobId, deadline, null, null);

            assertEquals(deadline, stored(sessionId).deadline());
            assertEquals(ThumbnailResultOutcome.APPLIED, service.apply(request(jobId, sessionId)).outcome());
            assertEquals(localBase(), stored(sessionId).updatedAt());
            assertEquals(localBase(), sessions.findById(sessionId).orElseThrow().getThumbnailUpdatedAt());
            assertTrue(sessions.findDueThumbnailSessionIds(localBase().minusSeconds(1), PageRequest.of(0, 10)).isEmpty());
            assertEquals(List.of(sessionId),
                    sessions.findDueThumbnailSessionIds(localBase(), PageRequest.of(0, 10)));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void appliesAcceptedJobAndNormalizesThePublicBaseUrlSlash() {
        UUID jobId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        saveSession(sessionId, StreamSessionStatus.LIVE, null,
                ThumbnailJobStatus.ACCEPTED, jobId, localBase().plusSeconds(120), null, null);
        properties.setPublicBaseUrl("https://thumbnails.example.com/base///");

        try {
            assertEquals(ThumbnailResultOutcome.APPLIED,
                    service.apply(request(jobId, sessionId)).outcome());
            assertEquals("https://thumbnails.example.com/base/"
                            + objectKey(sessionId, jobId),
                    stored(sessionId).url());
        } finally {
            properties.setPublicBaseUrl(BASE_URL);
        }
    }

    @Test
    void ignoresMissingOldExpiredFailedAndNonLiveSessionsWithoutTouchingTheThumbnail() {
        UUID missingJobId = UUID.randomUUID();
        UUID missingSessionId = UUID.randomUUID();
        assertEquals(ThumbnailResultOutcome.IGNORED,
                service.apply(request(missingJobId, missingSessionId)).outcome());

        UUID oldJobId = UUID.randomUUID();
        UUID currentJobId = UUID.randomUUID();
        UUID replacedSessionId = UUID.randomUUID();
        LocalDateTime previousUpdate = localBase().minusSeconds(30);
        saveSession(replacedSessionId, StreamSessionStatus.LIVE, previousUpdate,
                ThumbnailJobStatus.ACCEPTED, currentJobId, localBase().plusSeconds(120),
                "https://thumbnails.example.com/old.jpg", "old-key");
        assertEquals(ThumbnailResultOutcome.IGNORED,
                service.apply(request(oldJobId, replacedSessionId)).outcome());
        Stored replaced = stored(replacedSessionId);
        assertEquals(previousUpdate, replaced.updatedAt());
        assertEquals("https://thumbnails.example.com/old.jpg", replaced.url());
        assertEquals("old-key", replaced.objectKey());
        assertEquals("ACCEPTED", replaced.jobStatus());

        UUID failedJobId = UUID.randomUUID();
        UUID failedSessionId = UUID.randomUUID();
        saveSession(failedSessionId, StreamSessionStatus.LIVE, previousUpdate,
                ThumbnailJobStatus.FAILED, failedJobId, localBase().plusSeconds(120), null, null);
        assertEquals(ThumbnailResultOutcome.IGNORED,
                service.apply(request(failedJobId, failedSessionId)).outcome());
        assertUnchanged(failedSessionId, previousUpdate);

        UUID endedJobId = UUID.randomUUID();
        UUID endedSessionId = UUID.randomUUID();
        saveSession(endedSessionId, StreamSessionStatus.ENDED, previousUpdate,
                ThumbnailJobStatus.ACCEPTED, endedJobId, localBase().plusSeconds(120), null, null);
        assertEquals(ThumbnailResultOutcome.IGNORED,
                service.apply(request(endedJobId, endedSessionId)).outcome());
        assertUnchanged(endedSessionId, previousUpdate);

        UUID expiredJobId = UUID.randomUUID();
        UUID expiredSessionId = UUID.randomUUID();
        saveSession(expiredSessionId, StreamSessionStatus.LIVE, previousUpdate,
                ThumbnailJobStatus.ACCEPTED, expiredJobId, localBase(), null, null);
        assertEquals(ThumbnailResultOutcome.IGNORED,
                service.apply(request(expiredJobId, expiredSessionId)).outcome());
        assertUnchanged(expiredSessionId, previousUpdate);
    }

    @Test
    void appliesOnlyOneOfTwoConcurrentCallbacksForTheSameJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        saveSession(sessionId, StreamSessionStatus.LIVE, null,
                ThumbnailJobStatus.ACCEPTED, jobId, localBase().plusSeconds(120), null, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ThumbnailResultResponse>> futures = List.of(
                    pool.submit(() -> {
                        start.await();
                        return service.apply(request(jobId, sessionId));
                    }),
                    pool.submit(() -> {
                        start.await();
                        return service.apply(request(jobId, sessionId));
                    }));
            start.countDown();
            List<ThumbnailResultOutcome> outcomes = List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS).outcome(),
                    futures.get(1).get(10, TimeUnit.SECONDS).outcome());

            assertEquals(1, outcomes.stream().filter(ThumbnailResultOutcome.APPLIED::equals).count());
            assertEquals(1, outcomes.stream().filter(ThumbnailResultOutcome.IGNORED::equals).count());
            assertEquals(ThumbnailJobStatus.SUCCEEDED, storedStatus(sessionId));
            assertEquals(localBase(), stored(sessionId).updatedAt());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void readsTheClockAfterWaitingForTheRowLock() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        saveSession(sessionId, StreamSessionStatus.LIVE, null,
                ThumbnailJobStatus.ACCEPTED, jobId, localBase().plusSeconds(5), null, null);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ThumbnailResultResponse> callback = new TransactionTemplate(transactionManager).execute(status -> {
                sessions.findByIdForUpdate(sessionId).orElseThrow();
                CountDownLatch started = new CountDownLatch(1);
                Future<ThumbnailResultResponse> pending = pool.submit(() -> {
                    started.countDown();
                    return service.apply(request(jobId, sessionId));
                });
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    Thread.sleep(300);
                    assertTrue(!pending.isDone());
                    clock.instant = BASE.plusSeconds(5);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return pending;
            });
            assertEquals(ThumbnailResultOutcome.IGNORED,
                    callback.get(10, TimeUnit.SECONDS).outcome());
            assertUnchanged(sessionId, null);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aCallbackBeforeAckCannotBeRevertedByTheScheduler() {
        UUID jobId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        saveSession(sessionId, StreamSessionStatus.LIVE, null,
                ThumbnailJobStatus.PENDING_DISPATCH, jobId, localBase().plusSeconds(120), null, null);

        assertEquals(ThumbnailResultOutcome.APPLIED,
                service.apply(request(jobId, sessionId)).outcome());
        assertEquals(0, dispatchService.dispatchDueJobs());

        Stored stored = stored(sessionId);
        assertEquals("SUCCEEDED", stored.jobStatus());
        assertEquals(jobId, stored.jobId());
        assertEquals(localBase(), stored.updatedAt());
        assertEquals(BASE_URL + objectKey(sessionId, jobId), stored.url());
    }

    @Test
    void rollsBackEveryThumbnailFieldWhenTheCommitFails() {
        UUID jobId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        LocalDateTime previousUpdate = localBase().minusSeconds(30);
        saveSession(sessionId, StreamSessionStatus.LIVE, previousUpdate,
                ThumbnailJobStatus.ACCEPTED, jobId, localBase().plusSeconds(120),
                "https://thumbnails.example.com/previous.jpg", "previous-key");
        jdbc.execute("""
                CREATE FUNCTION reject_thumbnail_result() RETURNS trigger AS $$
                BEGIN
                    IF NEW.thumbnail_job_status = 'SUCCEEDED' THEN
                        RAISE EXCEPTION 'forced thumbnail commit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_thumbnail_result
                BEFORE UPDATE ON stream_sessions
                FOR EACH ROW EXECUTE FUNCTION reject_thumbnail_result()
                """);
        try {
            assertThrows(DataAccessException.class,
                    () -> service.apply(request(jobId, sessionId)));
            Stored stored = stored(sessionId);
            assertEquals("ACCEPTED", stored.jobStatus());
            assertEquals(previousUpdate, stored.updatedAt());
            assertEquals("https://thumbnails.example.com/previous.jpg", stored.url());
            assertEquals("previous-key", stored.objectKey());
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_thumbnail_result ON stream_sessions");
            jdbc.execute("DROP FUNCTION IF EXISTS reject_thumbnail_result()");
        }
    }

    private void assertUnchanged(UUID sessionId, LocalDateTime expectedUpdate) {
        Stored stored = stored(sessionId);
        if (expectedUpdate == null) {
            assertNull(stored.updatedAt());
        } else {
            assertEquals(expectedUpdate, stored.updatedAt());
        }
        assertNull(stored.url());
        assertNull(stored.objectKey());
    }

    private ThumbnailJobStatus storedStatus(UUID sessionId) {
        return ThumbnailJobStatus.valueOf(stored(sessionId).jobStatus());
    }

    private Stored stored(UUID sessionId) {
        return jdbc.queryForObject("""
                SELECT thumbnail_url, thumbnail_object_key, thumbnail_updated_at,
                       thumbnail_job_status, thumbnail_job_id, thumbnail_job_deadline_at
                FROM stream_sessions
                WHERE id = ?
                """, (rs, rowNum) -> new Stored(
                rs.getString("thumbnail_url"),
                rs.getString("thumbnail_object_key"),
                rs.getObject("thumbnail_updated_at", LocalDateTime.class),
                rs.getString("thumbnail_job_status"),
                rs.getObject("thumbnail_job_id", UUID.class),
                rs.getObject("thumbnail_job_deadline_at", LocalDateTime.class)
        ), sessionId);
    }

    private ThumbnailResultRequest request(UUID jobId, UUID sessionId) {
        return new ThumbnailResultRequest(
                jobId, sessionId, objectKey(sessionId, jobId),
                "image/jpeg", 48_210, 640, 360);
    }

    private String objectKey(UUID sessionId, UUID jobId) {
        return "livestream-thumbnails/" + sessionId + "/" + jobId + ".jpg";
    }

    private void saveSession(
            UUID sessionId,
            StreamSessionStatus status,
            LocalDateTime thumbnailUpdatedAt,
            ThumbnailJobStatus jobStatus,
            UUID jobId,
            LocalDateTime deadline,
            String thumbnailUrl,
            String thumbnailObjectKey
    ) {
        sessions.saveAndFlush(StreamSessionEntity.builder()
                .id(sessionId)
                .channelId(UUID.randomUUID())
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .title("Thumbnail result " + status)
                .visibility(StreamVisibility.PUBLIC)
                .status(status)
                .playbackPath("/live/" + sessionId + "/index.m3u8")
                .creationRequestId(UUID.randomUUID())
                .mediaPathState(status == StreamSessionStatus.ENDED ? MediaPathState.CLOSED : MediaPathState.OPEN)
                .mediaSourceId(UUID.randomUUID())
                .mediaSourceType(MediaSourceType.RTMP_CONN)
                .startedAt(localBase().minusMinutes(10))
                .endedAt(status == StreamSessionStatus.ENDED ? localBase() : null)
                .thumbnailUrl(thumbnailUrl)
                .thumbnailObjectKey(thumbnailObjectKey)
                .thumbnailUpdatedAt(thumbnailUpdatedAt)
                .thumbnailJobId(jobId)
                .thumbnailJobStatus(jobStatus)
                .thumbnailJobDeadlineAt(deadline)
                .build());
    }

    private LocalDateTime localBase() {
        return LocalDateTime.ofInstant(BASE, ZoneOffset.UTC);
    }

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

    private record Stored(
            String url,
            String objectKey,
            LocalDateTime updatedAt,
            String jobStatus,
            UUID jobId,
            LocalDateTime deadline
    ) {
    }
}
