package com.reallive.streamservice.stream.service;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.stream.MediaTestSupport;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.entity.enums.ThumbnailJobStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.scheduler.ThumbnailDispatchScheduler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class ThumbnailDispatchPostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();

    private static final Instant BASE = Instant.parse("2026-11-05T12:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutorService SERVER_EXECUTOR = Executors.newCachedThreadPool();
    private static final HttpServer SERVER = server();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final List<Captured> CAPTURED = new CopyOnWriteArrayList<>();
    private static volatile int responseStatus;
    private static volatile long responseDelayMillis;
    private static volatile String rawResponseBody;
    private static volatile Function<BatchPayload, Object> responder;
    private static volatile Consumer<Captured> beforeResponse;
    private static volatile JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        configureDatabase(registry, SCHEMA);
        registry.add("stream.thumbnail.enabled", () -> "false");
        registry.add("stream.thumbnail.interval", () -> "60s");
        registry.add("stream.thumbnail.scheduler-delay", () -> "1h");
        registry.add("stream.thumbnail.batch-size", () -> "50");
        registry.add("stream.thumbnail.job-deadline", () -> "240s");
        registry.add("stream.thumbnail.worker-base-url", () -> workerBase());
        registry.add("stream.thumbnail.internal-hls-base-url", () -> "http://media-server:8888");
        registry.add("stream.thumbnail.worker-connect-timeout", () -> "500ms");
        registry.add("stream.thumbnail.worker-read-timeout", () -> "500ms");
    }

    @AfterAll
    static void cleanup() throws SQLException {
        try {
            dropSchema(SCHEMA);
        } finally {
            SERVER.stop(0);
            SERVER_EXECUTOR.shutdownNow();
        }
    }

    @Autowired
    private ThumbnailDispatchService dispatch;

    @Autowired
    private StreamSessionRepository sessions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ControlledClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void reset() {
        jdbcTemplate = jdbc;
        jdbc.execute("DELETE FROM stream_sessions");
        clock.instant = BASE;
        REQUESTS.set(0);
        CAPTURED.clear();
        responseStatus = 202;
        responseDelayMillis = 0;
        rawResponseBody = null;
        responder = ThumbnailDispatchPostgresTest::acceptAll;
        beforeResponse = captured -> {
        };
    }

    @Test
    void thumbnailSchedulerIsDisabledByDefaultInTheTestProfile() {
        assertFalse(context.containsBean("thumbnailTaskScheduler"));
        assertTrue(context.getBeansOfType(ThumbnailDispatchScheduler.class).isEmpty());
        assertTrue(context.containsBean("thumbnailDispatchServiceImpl"));
    }

    @Test
    void dispatchesDueSessionWithExactContractAndCommitsBeforeHttp() throws Exception {
        StreamSessionEntity session = saveLive(null, null, null, null);
        AtomicReference<String> observed = new AtomicReference<>();
        beforeResponse = captured -> {
            JobPayload job = captured.batch().jobs().getFirst();
            String status = jdbcTemplate.queryForObject(
                    "select thumbnail_job_status from stream_sessions where id = ?", String.class, job.sessionId());
            UUID jobId = jdbcTemplate.queryForObject(
                    "select thumbnail_job_id from stream_sessions where id = ?", UUID.class, job.sessionId());
            observed.set(status + ":" + jobId);
            jdbcTemplate.update("update stream_sessions set title = ? where id = ?",
                    "Changed during HTTP", job.sessionId());
        };

        int sent = dispatch.dispatchDueJobs();

        assertEquals(1, sent);
        assertEquals(1, REQUESTS.get());
        Captured captured = CAPTURED.getFirst();
        assertNull(captured.internalToken());
        assertTrue(captured.contentType().startsWith("application/json"));
        assertEquals(1, captured.batch().jobs().size());
        JobPayload job = captured.batch().jobs().getFirst();
        assertEquals(session.getId(), job.sessionId());
        assertEquals("http://media-server:8888/live/" + job.sessionId() + "/index.m3u8", job.playbackUrl());
        assertFalse(job.playbackUrl().contains("/live/live/"));
        assertEquals(BASE.plusSeconds(300).toString(), job.readExpiresAt());
        assertEquals(BASE.plusSeconds(240).toString(), job.jobDeadlineAt());
        assertEquals("PENDING_DISPATCH:" + job.jobId(), observed.get());

        SignedJWT token = SignedJWT.parse(job.readToken());
        assertTrue(token.verify(new RSASSAVerifier((RSAPublicKey) MediaTestSupport.KEYS.getPublic())));
        assertEquals("media-worker", token.getJWTClaimsSet().getSubject());
        assertEquals(List.of(Map.of("action", "read", "path", "live/" + session.getId())),
                token.getJWTClaimsSet().getClaim("mediamtx_permissions"));
        assertEquals(BASE.plusSeconds(300), token.getJWTClaimsSet().getExpirationTime().toInstant());

        StreamSessionEntity stored = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.ACCEPTED, stored.getThumbnailJobStatus());
        assertEquals(job.jobId(), stored.getThumbnailJobId());
        assertEquals(BASE.plusSeconds(240), stored.getThumbnailJobDeadlineAt().toInstant(ZoneOffset.UTC));
        assertNull(stored.getThumbnailUpdatedAt());
        assertEquals("Changed during HTTP", stored.getTitle());
    }

    @Test
    void selectsFirstLiveImmediatelyAndSelectsExactlyAtCutoffButNotRecentlyUpdated() {
        StreamSessionEntity firstLive = saveLive(null, null, null, null);
        StreamSessionEntity exactlyAtCutoff = saveLive(localBase().minusSeconds(60),
                ThumbnailJobStatus.SUCCEEDED, UUID.randomUUID(), localBase().plusSeconds(600));
        StreamSessionEntity recent = saveLive(localBase().minusSeconds(1),
                ThumbnailJobStatus.SUCCEEDED, UUID.randomUUID(), localBase().plusSeconds(600));

        assertEquals(2, dispatch.dispatchDueJobs());

        Set<UUID> dispatchedSessions = CAPTURED.getFirst().batch().jobs().stream()
                .map(JobPayload::sessionId)
                .collect(Collectors.toSet());
        assertTrue(dispatchedSessions.contains(firstLive.getId()));
        assertTrue(dispatchedSessions.contains(exactlyAtCutoff.getId()));
        assertFalse(dispatchedSessions.contains(recent.getId()));

        StreamSessionEntity storedFirst = sessions.findById(firstLive.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.ACCEPTED, storedFirst.getThumbnailJobStatus());
        assertNotNull(storedFirst.getThumbnailJobId());
        assertEquals(BASE.plusSeconds(240), storedFirst.getThumbnailJobDeadlineAt().toInstant(ZoneOffset.UTC));

        StreamSessionEntity storedRecent = sessions.findById(recent.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.SUCCEEDED, storedRecent.getThumbnailJobStatus());
        assertEquals(BASE.plusSeconds(600), storedRecent.getThumbnailJobDeadlineAt().toInstant(ZoneOffset.UTC));
    }

    @Test
    void neverDispatchesNonLiveSessionsOrAcceptedJobs() {
        StreamSessionEntity reconnecting = saveSession(StreamSessionStatus.RECONNECTING, MediaPathState.OPEN,
                null, null, null, null, null, null);
        StreamSessionEntity ended = saveSession(StreamSessionStatus.ENDED, MediaPathState.CLOSED,
                null, null, null, null, null, null);
        UUID acceptedJobId = UUID.randomUUID();
        LocalDateTime acceptedDeadline = localBase().plusSeconds(100);
        StreamSessionEntity accepted = saveLive(null, ThumbnailJobStatus.ACCEPTED,
                acceptedJobId, acceptedDeadline);

        assertEquals(0, dispatch.dispatchDueJobs());
        assertEquals(0, REQUESTS.get());

        for (StreamSessionEntity session : List.of(reconnecting, ended)) {
            StreamSessionEntity stored = sessions.findById(session.getId()).orElseThrow();
            assertNull(stored.getThumbnailJobId());
            assertNull(stored.getThumbnailJobStatus());
            assertNull(stored.getThumbnailJobDeadlineAt());
        }
        StreamSessionEntity storedAccepted = sessions.findById(accepted.getId()).orElseThrow();
        assertEquals(acceptedJobId, storedAccepted.getThumbnailJobId());
        assertEquals(ThumbnailJobStatus.ACCEPTED, storedAccepted.getThumbnailJobStatus());
        assertEquals(acceptedDeadline, storedAccepted.getThumbnailJobDeadlineAt());
    }

    @Test
    void resendsPendingJobWithTheSameIdAndOriginalDeadline() {
        UUID jobId = UUID.randomUUID();
        LocalDateTime deadline = localBase().plusSeconds(100);
        StreamSessionEntity session = saveLive(null, ThumbnailJobStatus.PENDING_DISPATCH, jobId, deadline);

        assertEquals(1, dispatch.dispatchDueJobs());

        JobPayload job = CAPTURED.getFirst().batch().jobs().getFirst();
        assertEquals(jobId, job.jobId());
        assertEquals(deadline.toInstant(ZoneOffset.UTC).toString(), job.jobDeadlineAt());
        StreamSessionEntity stored = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.ACCEPTED, stored.getThumbnailJobStatus());
        assertEquals(jobId, stored.getThumbnailJobId());
        assertEquals(deadline, stored.getThumbnailJobDeadlineAt());
        assertNull(stored.getThumbnailUpdatedAt());

        assertEquals(0, dispatch.dispatchDueJobs());
        assertEquals(1, REQUESTS.get());
    }

    @Test
    void appliesPartialResponseAndKeepsQueueFullPendingForTheNextPass() {
        saveLive(null, null, null, null);
        saveLive(null, null, null, null);
        saveLive(null, null, null, null);
        responder = batch -> {
            List<JobPayload> jobs = batch.jobs();
            return Map.of(
                    "acceptedJobIds", List.of(jobs.get(0).jobId(), jobs.get(1).jobId()),
                    "rejectedJobs", List.of(Map.of("jobId", jobs.get(2).jobId(), "reason", "QUEUE_FULL"))
            );
        };

        assertEquals(3, dispatch.dispatchDueJobs());

        List<JobPayload> jobs = CAPTURED.getFirst().batch().jobs();
        assertEquals(ThumbnailJobStatus.ACCEPTED,
                sessions.findById(jobs.get(0).sessionId()).orElseThrow().getThumbnailJobStatus());
        assertEquals(ThumbnailJobStatus.ACCEPTED,
                sessions.findById(jobs.get(1).sessionId()).orElseThrow().getThumbnailJobStatus());
        StreamSessionEntity pending = sessions.findById(jobs.get(2).sessionId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.PENDING_DISPATCH, pending.getThumbnailJobStatus());
        UUID pendingJobId = pending.getThumbnailJobId();
        LocalDateTime pendingDeadline = pending.getThumbnailJobDeadlineAt();
        assertNotNull(pendingJobId);

        responder = ThumbnailDispatchPostgresTest::acceptAll;
        assertEquals(1, dispatch.dispatchDueJobs());
        JobPayload retried = CAPTURED.get(1).batch().jobs().getFirst();
        assertEquals(pendingJobId, retried.jobId());
        assertEquals(pendingDeadline.toInstant(ZoneOffset.UTC).toString(), retried.jobDeadlineAt());
        assertEquals(ThumbnailJobStatus.ACCEPTED, sessions.findById(pending.getId()).orElseThrow().getThumbnailJobStatus());
        assertEquals(2, REQUESTS.get());
    }

    @Test
    void appliesOnlyAcceptedIdsFromTheCurrentBatchAndIgnoresUnknownIds() {
        saveLive(null, null, null, null);
        saveLive(null, null, null, null);
        responder = batch -> Map.of(
                "acceptedJobIds", List.of(batch.jobs().getFirst().jobId(), UUID.randomUUID()),
                "rejectedJobs", List.of()
        );

        assertEquals(2, dispatch.dispatchDueJobs());

        List<JobPayload> jobs = CAPTURED.getFirst().batch().jobs();
        assertEquals(ThumbnailJobStatus.ACCEPTED,
                sessions.findById(jobs.get(0).sessionId()).orElseThrow().getThumbnailJobStatus());
        assertEquals(ThumbnailJobStatus.PENDING_DISPATCH,
                sessions.findById(jobs.get(1).sessionId()).orElseThrow().getThumbnailJobStatus());
    }

    @Test
    void doesNotAcknowledgeMissingAcceptedListOrAnIdThatIsAlsoRejected() {
        StreamSessionEntity session = saveLive(null, null, null, null);
        responder = captured -> Map.of("rejectedJobs", List.of());

        assertEquals(1, dispatch.dispatchDueJobs());
        StreamSessionEntity afterMissingAccepted = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.PENDING_DISPATCH, afterMissingAccepted.getThumbnailJobStatus());
        UUID jobId = afterMissingAccepted.getThumbnailJobId();
        LocalDateTime deadline = afterMissingAccepted.getThumbnailJobDeadlineAt();

        responder = batch -> {
            UUID current = batch.jobs().getFirst().jobId();
            return Map.of(
                    "acceptedJobIds", List.of(current),
                    "rejectedJobs", List.of(Map.of("jobId", current, "reason", "QUEUE_FULL"))
            );
        };
        assertEquals(1, dispatch.dispatchDueJobs());
        StreamSessionEntity afterConflict = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.PENDING_DISPATCH, afterConflict.getThumbnailJobStatus());
        assertEquals(jobId, afterConflict.getThumbnailJobId());
        assertEquals(deadline, afterConflict.getThumbnailJobDeadlineAt());

        responder = ThumbnailDispatchPostgresTest::acceptAll;
        assertEquals(1, dispatch.dispatchDueJobs());
        StreamSessionEntity accepted = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.ACCEPTED, accepted.getThumbnailJobStatus());
        assertEquals(jobId, accepted.getThumbnailJobId());
        assertEquals(deadline, accepted.getThumbnailJobDeadlineAt());
        assertEquals(3, REQUESTS.get());
    }

    @Test
    void keepsPendingOnMalformedResponseAndRecoversWithTheSameJob() {
        StreamSessionEntity session = saveLive(null, null, null, null);
        rawResponseBody = "{\"acceptedJobIds\": [";

        assertEquals(1, dispatch.dispatchDueJobs());
        StreamSessionEntity pending = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.PENDING_DISPATCH, pending.getThumbnailJobStatus());
        UUID jobId = pending.getThumbnailJobId();

        rawResponseBody = null;
        responder = ThumbnailDispatchPostgresTest::acceptAll;
        assertEquals(1, dispatch.dispatchDueJobs());
        StreamSessionEntity accepted = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.ACCEPTED, accepted.getThumbnailJobStatus());
        assertEquals(jobId, accepted.getThumbnailJobId());
        assertEquals(2, REQUESTS.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 503})
    void keepsPendingOnHttpErrorWithoutRetryingAndRecovers(int status) {
        StreamSessionEntity session = saveLive(null, null, null, null);
        responseStatus = status;
        rawResponseBody = "{\"statusCode\":" + status + ",\"code\":\"TEST\"}";

        assertEquals(1, dispatch.dispatchDueJobs());
        assertEquals(1, REQUESTS.get());
        StreamSessionEntity pending = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.PENDING_DISPATCH, pending.getThumbnailJobStatus());
        UUID jobId = pending.getThumbnailJobId();
        LocalDateTime deadline = pending.getThumbnailJobDeadlineAt();

        responseStatus = 202;
        rawResponseBody = null;
        responder = ThumbnailDispatchPostgresTest::acceptAll;
        assertEquals(1, dispatch.dispatchDueJobs());

        StreamSessionEntity accepted = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.ACCEPTED, accepted.getThumbnailJobStatus());
        assertEquals(jobId, accepted.getThumbnailJobId());
        assertEquals(deadline, accepted.getThumbnailJobDeadlineAt());
        assertEquals(2, REQUESTS.get());
    }

    @Test
    void keepsPendingOnReadTimeoutWithoutRetryingAndRecovers() {
        StreamSessionEntity session = saveLive(null, null, null, null);
        responseDelayMillis = 1500;

        assertEquals(1, dispatch.dispatchDueJobs());
        assertEquals(1, REQUESTS.get());
        StreamSessionEntity pending = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.PENDING_DISPATCH, pending.getThumbnailJobStatus());
        UUID jobId = pending.getThumbnailJobId();

        responseDelayMillis = 0;
        responder = ThumbnailDispatchPostgresTest::acceptAll;
        assertEquals(1, dispatch.dispatchDueJobs());

        StreamSessionEntity accepted = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.ACCEPTED, accepted.getThumbnailJobStatus());
        assertEquals(jobId, accepted.getThumbnailJobId());
        assertEquals(2, REQUESTS.get());
    }

    @Test
    void keepsPendingWhenTheWorkerResponseHandlerFails() {
        StreamSessionEntity session = saveLive(null, null, null, null);
        rawResponseBody = null;
        responseStatus = 202;
        responder = captured -> {
            throw new IllegalStateException("Simulate a broken worker response");
        };

        assertEquals(1, dispatch.dispatchDueJobs());
        assertEquals(1, REQUESTS.get());
        StreamSessionEntity pending = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.PENDING_DISPATCH, pending.getThumbnailJobStatus());
        assertNotNull(pending.getThumbnailJobId());
        assertNull(pending.getThumbnailUpdatedAt());
    }

    @Test
    void expiresWaitingJobsPreservingJobAndImageAndLeavesSucceededUntouched() {
        LocalDateTime imageTime = localBase().minusSeconds(1);
        UUID pendingJobId = UUID.randomUUID();
        LocalDateTime pendingDeadline = localBase().minusSeconds(1);
        StreamSessionEntity pending = saveSession(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                imageTime, ThumbnailJobStatus.PENDING_DISPATCH, pendingJobId, pendingDeadline,
                "https://cdn.example.com/pending.jpg", "pending.jpg");
        UUID acceptedJobId = UUID.randomUUID();
        LocalDateTime acceptedDeadline = localBase().minusSeconds(1);
        StreamSessionEntity accepted = saveSession(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                imageTime, ThumbnailJobStatus.ACCEPTED, acceptedJobId, acceptedDeadline,
                "https://cdn.example.com/accepted.jpg", "accepted.jpg");
        UUID succeededJobId = UUID.randomUUID();
        LocalDateTime succeededDeadline = localBase().minusSeconds(1);
        StreamSessionEntity succeeded = saveSession(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                imageTime, ThumbnailJobStatus.SUCCEEDED, succeededJobId, succeededDeadline,
                "https://cdn.example.com/succeeded.jpg", "succeeded.jpg");

        assertEquals(0, dispatch.dispatchDueJobs());
        assertEquals(0, REQUESTS.get());

        assertExpiredButPreserved(pending, pendingJobId, pendingDeadline, "https://cdn.example.com/pending.jpg", imageTime);
        assertExpiredButPreserved(accepted, acceptedJobId, acceptedDeadline, "https://cdn.example.com/accepted.jpg", imageTime);

        StreamSessionEntity storedSucceeded = sessions.findById(succeeded.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.SUCCEEDED, storedSucceeded.getThumbnailJobStatus());
        assertEquals(succeededJobId, storedSucceeded.getThumbnailJobId());
        assertEquals(succeededDeadline, storedSucceeded.getThumbnailJobDeadlineAt());
        assertEquals("https://cdn.example.com/succeeded.jpg", storedSucceeded.getThumbnailUrl());
        assertEquals(imageTime, storedSucceeded.getThumbnailUpdatedAt());
    }

    @Test
    void preservesCallbackSuccessThatArrivesBeforeTheAck() {
        StreamSessionEntity session = saveLive(null, null, null, null);
        LocalDateTime callbackAt = localBase();
        beforeResponse = captured -> transaction().executeWithoutResult(status -> {
            StreamSessionEntity current = sessions
                    .findById(captured.batch().jobs().getFirst().sessionId()).orElseThrow();
            current.setThumbnailJobStatus(ThumbnailJobStatus.SUCCEEDED);
            current.setThumbnailUrl("https://cdn.example.com/thumb.jpg");
            current.setThumbnailObjectKey("thumb.jpg");
            current.setThumbnailUpdatedAt(callbackAt);
            sessions.saveAndFlush(current);
        });

        assertEquals(1, dispatch.dispatchDueJobs());

        StreamSessionEntity stored = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.SUCCEEDED, stored.getThumbnailJobStatus());
        assertEquals("https://cdn.example.com/thumb.jpg", stored.getThumbnailUrl());
        assertEquals("thumb.jpg", stored.getThumbnailObjectKey());
        assertEquals(callbackAt, stored.getThumbnailUpdatedAt());
        assertEquals(CAPTURED.getFirst().batch().jobs().getFirst().jobId(), stored.getThumbnailJobId());
    }

    @Test
    void doesNotOverwriteAFailedJobFromALateAck() {
        StreamSessionEntity session = saveLive(null, null, null, null);
        beforeResponse = captured -> jdbcTemplate.update(
                "update stream_sessions set thumbnail_job_status = 'FAILED' where id = ?",
                captured.batch().jobs().getFirst().sessionId());

        assertEquals(1, dispatch.dispatchDueJobs());

        StreamSessionEntity stored = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.FAILED, stored.getThumbnailJobStatus());
        assertEquals(CAPTURED.getFirst().batch().jobs().getFirst().jobId(), stored.getThumbnailJobId());
    }

    @Test
    void doesNotRestoreAReplacedJobIdFromALateAck() {
        StreamSessionEntity session = saveLive(null, null, null, null);
        UUID replacementJobId = UUID.randomUUID();
        LocalDateTime replacementDeadline = localBase().plusSeconds(1000);
        beforeResponse = captured -> transaction().executeWithoutResult(status -> {
            StreamSessionEntity current = sessions
                    .findById(captured.batch().jobs().getFirst().sessionId()).orElseThrow();
            current.setThumbnailJobId(replacementJobId);
            current.setThumbnailJobStatus(ThumbnailJobStatus.PENDING_DISPATCH);
            current.setThumbnailJobDeadlineAt(replacementDeadline);
            sessions.saveAndFlush(current);
        });

        assertEquals(1, dispatch.dispatchDueJobs());

        StreamSessionEntity stored = sessions.findById(session.getId()).orElseThrow();
        assertEquals(replacementJobId, stored.getThumbnailJobId());
        assertEquals(ThumbnailJobStatus.PENDING_DISPATCH, stored.getThumbnailJobStatus());
        assertEquals(replacementDeadline, stored.getThumbnailJobDeadlineAt());
    }

    @Test
    void concurrentPassesCreateAtMostOneJobIdForTheSameSession() throws Exception {
        StreamSessionEntity session = saveLive(null, null, null, null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> pass = () -> {
            start.await(2, TimeUnit.SECONDS);
            return dispatch.dispatchDueJobs();
        };

        try {
            Future<Integer> first = executor.submit(pass);
            Future<Integer> second = executor.submit(pass);
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Set<UUID> jobIds = CAPTURED.stream()
                .flatMap(captured -> captured.batch().jobs().stream())
                .map(JobPayload::jobId)
                .collect(Collectors.toSet());
        assertEquals(1, jobIds.size());
        StreamSessionEntity stored = sessions.findById(session.getId()).orElseThrow();
        assertEquals(jobIds.iterator().next(), stored.getThumbnailJobId());
        assertEquals(ThumbnailJobStatus.ACCEPTED, stored.getThumbnailJobStatus());
    }

    @Test
    void enforcesTheBatchLimitAndMakesProgressAcrossPasses() {
        for (int index = 0; index < 55; index++) {
            saveLive(null, null, null, null);
        }

        assertEquals(50, dispatch.dispatchDueJobs());
        assertEquals(1, REQUESTS.get());
        assertEquals(50, CAPTURED.getFirst().batch().jobs().size());
        assertEquals(50, countJobs());

        assertEquals(5, dispatch.dispatchDueJobs());
        assertEquals(2, REQUESTS.get());
        assertEquals(5, CAPTURED.get(1).batch().jobs().size());
        assertEquals(55, countJobs());

        Set<UUID> allJobIds = CAPTURED.stream()
                .flatMap(captured -> captured.batch().jobs().stream())
                .map(JobPayload::jobId)
                .collect(Collectors.toSet());
        assertEquals(55, allJobIds.size());
    }

    private void assertExpiredButPreserved(
            StreamSessionEntity original,
            UUID jobId,
            LocalDateTime deadline,
            String thumbnailUrl,
            LocalDateTime imageTime
    ) {
        StreamSessionEntity stored = sessions.findById(original.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.FAILED, stored.getThumbnailJobStatus());
        assertEquals(jobId, stored.getThumbnailJobId());
        assertEquals(deadline, stored.getThumbnailJobDeadlineAt());
        assertEquals(thumbnailUrl, stored.getThumbnailUrl());
        assertEquals(imageTime, stored.getThumbnailUpdatedAt());
    }

    private int countJobs() {
        Integer count = jdbc.queryForObject(
                "select count(*) from stream_sessions where thumbnail_job_id is not null", Integer.class);
        return count == null ? 0 : count;
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private StreamSessionEntity saveLive(
            LocalDateTime thumbnailUpdatedAt,
            ThumbnailJobStatus jobStatus,
            UUID jobId,
            LocalDateTime deadline
    ) {
        return saveSession(StreamSessionStatus.LIVE, MediaPathState.OPEN,
                thumbnailUpdatedAt, jobStatus, jobId, deadline, null, null);
    }

    private StreamSessionEntity saveSession(
            StreamSessionStatus status,
            MediaPathState mediaPathState,
            LocalDateTime thumbnailUpdatedAt,
            ThumbnailJobStatus jobStatus,
            UUID jobId,
            LocalDateTime deadline,
            String thumbnailUrl,
            String thumbnailObjectKey
    ) {
        boolean hasJob = jobStatus != null;
        UUID sessionId = UUID.randomUUID();
        return sessions.saveAndFlush(StreamSessionEntity.builder()
                .id(sessionId)
                .channelId(UUID.randomUUID())
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .title("Thumbnail dispatch " + status)
                .visibility(StreamVisibility.PUBLIC)
                .status(status)
                .playbackPath("/live/" + sessionId + "/index.m3u8")
                .creationRequestId(UUID.randomUUID())
                .mediaPathState(mediaPathState)
                .mediaSourceId(UUID.randomUUID())
                .mediaSourceType(MediaSourceType.RTMP_CONN)
                .disconnectedAt(status == StreamSessionStatus.RECONNECTING ? localBase().minusSeconds(10) : null)
                .reconnectDeadlineAt(status == StreamSessionStatus.RECONNECTING ? localBase().plusSeconds(50) : null)
                .startedAt(localBase().minusMinutes(10))
                .endedAt(status == StreamSessionStatus.ENDED ? localBase() : null)
                .thumbnailUrl(thumbnailUrl)
                .thumbnailObjectKey(thumbnailObjectKey)
                .thumbnailUpdatedAt(thumbnailUpdatedAt)
                .thumbnailJobId(hasJob ? jobId : null)
                .thumbnailJobStatus(jobStatus)
                .thumbnailJobDeadlineAt(hasJob ? deadline : null)
                .build());
    }

    private static LocalDateTime localBase() {
        return LocalDateTime.ofInstant(BASE, ZoneOffset.UTC);
    }

    private static Object acceptAll(BatchPayload batch) {
        List<UUID> accepted = batch.jobs() == null ? List.of()
                : batch.jobs().stream().map(JobPayload::jobId).toList();
        return Map.of("acceptedJobIds", accepted, "rejectedJobs", List.of());
    }

    private static String workerBase() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(SERVER_EXECUTOR);
            server.createContext("/", ThumbnailDispatchPostgresTest::handle);
            server.start();
            return server;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void handle(HttpExchange exchange) {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String internalToken = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            BatchPayload batch = JSON.readValue(body, BatchPayload.class);
            Captured captured = new Captured(body, internalToken, contentType, batch);
            CAPTURED.add(captured);
            REQUESTS.incrementAndGet();

            int status = responseStatus;
            long delay = responseDelayMillis;
            beforeResponse.accept(captured);
            if (delay > 0) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(delay));
            }

            String raw = rawResponseBody;
            byte[] responseBytes = raw != null
                    ? raw.getBytes(StandardCharsets.UTF_8)
                    : status == 202
                    ? JSON.writeValueAsBytes(responder.apply(batch))
                    : JSON.writeValueAsBytes(Map.of(
                            "statusCode", status, "code", "TEST", "message", "test error"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, responseBytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(responseBytes);
            }
        } catch (Exception exception) {
            try {
                exchange.sendResponseHeaders(500, -1);
            } catch (Exception ignored) {
                // The client may already have timed out or closed the connection.
            }
        } finally {
            exchange.close();
        }
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

    private record Captured(
            String body,
            String internalToken,
            String contentType,
            BatchPayload batch
    ) {
        @Override
        public String toString() {
            return "Captured[internalToken=REDACTED, contentType=" + contentType + ", body=REDACTED]";
        }
    }

    private record BatchPayload(List<JobPayload> jobs) {
    }

    private record JobPayload(
            UUID jobId,
            UUID sessionId,
            String playbackUrl,
            String readToken,
            String readExpiresAt,
            String jobDeadlineAt
    ) {
        @Override
        public String toString() {
            return "JobPayload[jobId=" + jobId + ", sessionId=" + sessionId + ", readToken=REDACTED]";
        }
    }
}
