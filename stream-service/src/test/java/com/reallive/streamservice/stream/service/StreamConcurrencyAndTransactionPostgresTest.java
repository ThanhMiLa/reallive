package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.dto.CreateSessionCommand;
import com.reallive.streamservice.stream.dto.MediaOfflineCommand;
import com.reallive.streamservice.stream.dto.MediaOnlineCommand;
import com.reallive.streamservice.stream.dto.SessionCreationResult;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class StreamConcurrencyAndTransactionPostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final Instant BASE = Instant.parse("2026-10-25T12:00:00Z");

    @Autowired
    private StreamSessionDomainService domain;
    @Autowired
    private StreamSessionRepository sessions;
    @Autowired
    private StreamStatusHistoryRepository history;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ControlledClock clock;
    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        configureDatabase(registry, SCHEMA);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        dropSchema(SCHEMA);
    }

    @BeforeEach
    void resetClock() {
        clock.instant = BASE;
    }

    @Test
    void concurrentDifferentRequestsHaveOneWinner() throws Exception {
        var first = command();
        var other = new CreateSessionCommand(first.channelId(), first.streamerId(), first.ownerUserId(),
                first.categoryId(), "Competing stream", UUID.randomUUID());
        ExecutionException error = assertThrows(ExecutionException.class, () -> concurrentRetry(first, other));
        AppException conflict = assertInstanceOf(AppException.class, error.getCause());
        assertEquals(ErrorCode.STREAM_SESSION_ALREADY_ACTIVE, conflict.getErrorCode());
        assertEquals(1, jdbc.queryForObject("select count(*) from stream_sessions where channel_id=?", Integer.class, first.channelId()));
    }

    @Test
    void concurrentSameRequestReturnsWinnerAndPreservesBothMetadataFields() throws Exception {
        var first = command();
        var retry = new CreateSessionCommand(first.channelId(), first.streamerId(), first.ownerUserId(),
                UUID.randomUUID(), "Retry metadata must not overwrite", first.creationRequestId());
        var result = concurrentRetry(first, retry);
        assertFalse(result.created());
        var winner = sessions.findByOwnerUserIdAndCreationRequestId(first.ownerUserId(), first.creationRequestId()).orElseThrow();
        assertEquals(winner.getId(), result.session().getId());
        assertEquals(first.title(), result.session().getTitle());
        assertEquals(first.categoryId(), result.session().getCategoryId());
        assertEquals(1, history.findByStreamIdOrderByCreatedAtAscIdAsc(winner.getId()).size());
        assertEquals(LocalDateTime.ofInstant(BASE, ZoneOffset.UTC), history.findByStreamIdOrderByCreatedAtAscIdAsc(winner.getId()).getFirst().getCreatedAt());
    }

    @Test
    void concurrentRequestReuseForAnotherChannelReportsRequestConflict() throws Exception {
        var first = command();
        var retry = new CreateSessionCommand(UUID.randomUUID(), first.streamerId(), first.ownerUserId(),
                first.categoryId(), first.title(), first.creationRequestId());
        ExecutionException error = assertThrows(ExecutionException.class, () -> concurrentRetry(first, retry));
        assertEquals(ErrorCode.STREAM_CREATION_REQUEST_CONFLICT,
                assertInstanceOf(AppException.class, error.getCause()).getErrorCode());
        assertTrue(sessions.findSlotOccupyingByChannelId(retry.channelId()).isEmpty());
    }

    @Test
    void creationJoinsEnclosingTransactionAndRollsBackSessionAndHistoryTogether() {
        var command = command();
        var id = new AtomicReference<UUID>();
        assertThrows(IllegalStateException.class, () -> transaction().executeWithoutResult(status -> {
            var created = domain.createOrGetSession(command);
            id.set(created.session().getId());
            assertEquals(1, history.findByStreamIdOrderByCreatedAtAscIdAsc(id.get()).size());
            throw new IllegalStateException("Abort synchronous use case before commit");
        }));
        assertTrue(sessions.findById(id.get()).isEmpty());
        assertTrue(history.findByStreamIdOrderByCreatedAtAscIdAsc(id.get()).isEmpty());
        assertTrue(sessions.findSlotOccupyingByChannelId(command.channelId()).isEmpty());
        assertTrue(domain.createOrGetSession(command).created());
    }

    @Test
    void nonCreationConstraintErrorsAreNotMisreportedAsChannelConflicts() {
        var valid = command();
        var invalid = new CreateSessionCommand(valid.channelId(), valid.streamerId(), valid.ownerUserId(),
                valid.categoryId(), "   ", valid.creationRequestId());
        assertThrows(DataIntegrityViolationException.class, () -> domain.createOrGetSession(invalid));
        assertTrue(sessions.findSlotOccupyingByChannelId(valid.channelId()).isEmpty());
    }

    @Test
    void endRequiresEnclosingTransactionAndRollbackKeepsSlotAndHistory() {
        var command = command();
        UUID id = domain.createOrGetSession(command).session().getId();
        assertThrows(IllegalTransactionStateException.class, () -> domain.endSession(id, command.ownerUserId(), null));
        assertThrows(IllegalTransactionStateException.class, () -> domain.expireReconnectIfDue(id));
        assertThrows(IllegalStateException.class, () -> transaction().executeWithoutResult(status -> {
            sessions.findByIdForUpdate(id).orElseThrow();
            domain.endSession(id, command.ownerUserId(), "Domain mutation after closure precondition");
            throw new IllegalStateException("Abort enclosing transaction");
        }));
        assertEquals(StreamSessionStatus.READY, sessions.findById(id).orElseThrow().getStatus());
        assertEquals(MediaPathState.OPEN, sessions.findById(id).orElseThrow().getMediaPathState());
        assertEquals(1, history.findByStreamIdOrderByCreatedAtAscIdAsc(id).size());
        assertTrue(sessions.findSlotOccupyingByChannelId(command.channelId()).isPresent());
    }

    @Test
    void confirmedEndReleasesSlotAndIsTerminalAndIdempotent() {
        var command = command();
        UUID id = domain.createOrGetSession(command).session().getId();
        transaction().executeWithoutResult(status -> {
            sessions.findByIdForUpdate(id).orElseThrow();
            domain.endSession(id, command.ownerUserId(), "Closure already confirmed by caller");
        });
        var ended = sessions.findById(id).orElseThrow();
        assertEquals(StreamSessionStatus.ENDED, ended.getStatus());
        assertEquals(MediaPathState.CLOSED, ended.getMediaPathState());
        clock.advance(Duration.ofMinutes(1));
        transaction().executeWithoutResult(status -> domain.endSession(id, command.ownerUserId(), "Repeat"));
        domain.onMediaOnline(new MediaOnlineCommand(id, UUID.randomUUID(), MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        domain.onMediaOffline(new MediaOfflineCommand(id, UUID.randomUUID(), MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        assertEquals(ended.getEndedAt(), sessions.findById(id).orElseThrow().getEndedAt());
        assertEquals(2, history.findByStreamIdOrderByCreatedAtAscIdAsc(id).size());
        assertTrue(sessions.findCurrentByOwnerUserId(command.ownerUserId()).isEmpty());
        var next = new CreateSessionCommand(command.channelId(), command.streamerId(), command.ownerUserId(),
                command.categoryId(), "Next session", UUID.randomUUID());
        assertTrue(domain.createOrGetSession(next).created());
    }

    @Test
    void historyWriteFailureRollsBackEndInsteadOfLeavingStateWithoutHistory() {
        var command = command();
        UUID id = domain.createOrGetSession(command).session().getId();
        assertThrows(DataIntegrityViolationException.class, () -> transaction().executeWithoutResult(status ->
                domain.endSession(id, command.ownerUserId(), "x".repeat(501))));
        assertEquals(StreamSessionStatus.READY, sessions.findById(id).orElseThrow().getStatus());
        assertEquals(1, history.findByStreamIdOrderByCreatedAtAscIdAsc(id).size());
    }

    @Test
    void reconnectDeadlineSurvivesReloadAndExpiredOnlineDoesNotFalselyClosePath() {
        UUID id = domain.createOrGetSession(command()).session().getId();
        var source = UUID.randomUUID();
        var online = new MediaOnlineCommand(id, source, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null);
        domain.onMediaOnline(online);
        domain.onMediaOnline(online);
        LocalDateTime started = sessions.findById(id).orElseThrow().getStartedAt();
        var offline = new MediaOfflineCommand(id, source, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null);
        domain.onMediaOffline(offline);
        LocalDateTime deadline = sessions.findById(id).orElseThrow().getReconnectDeadlineAt();
        clock.advance(Duration.ofSeconds(20));
        domain.onMediaOffline(offline);
        assertEquals(deadline, sessions.findById(id).orElseThrow().getReconnectDeadlineAt());
        clock.advance(Duration.ofSeconds(40));
        domain.onMediaOnline(new MediaOnlineCommand(id, UUID.randomUUID(), MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        var reloaded = sessions.findById(id).orElseThrow();
        assertEquals(StreamSessionStatus.RECONNECTING, reloaded.getStatus());
        assertEquals(MediaPathState.OPEN, reloaded.getMediaPathState());
        assertEquals(deadline, reloaded.getReconnectDeadlineAt());
        assertEquals(started, reloaded.getStartedAt());
        assertEquals(3, history.findByStreamIdOrderByCreatedAtAscIdAsc(id).size());
        transaction().executeWithoutResult(status -> {
            sessions.findByIdForUpdate(id).orElseThrow();
            assertTrue(domain.expireReconnectIfDue(id).isPresent());
        });
        assertEquals(MediaPathState.CLOSED, sessions.findById(id).orElseThrow().getMediaPathState());
        assertEquals(StreamSessionStatus.ENDED, sessions.findById(id).orElseThrow().getStatus());
    }

    @Test
    void unauthorizedOwnerCannotMutateSessionAndNoTaskInfrastructureIsLoaded() {
        var command = command();
        UUID id = domain.createOrGetSession(command).session().getId();
        AppException error = assertThrows(AppException.class, () -> transaction().executeWithoutResult(status ->
                domain.endSession(id, UUID.randomUUID(), null)));
        assertEquals(ErrorCode.STREAM_SESSION_NOT_OWNED, error.getErrorCode());
        assertEquals(StreamSessionStatus.READY, sessions.findById(id).orElseThrow().getStatus());
        assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=? and table_name='stream_media_sync_tasks'", Integer.class, SCHEMA));
        assertTrue(Arrays.stream(context.getBeanDefinitionNames())
                .noneMatch(name -> name.toLowerCase().contains("mediasynctask")));
    }

    @Test
    void pessimisticLockMakesConcurrentOnlineWaitForEndCommit() throws Exception {
        var command = command();
        UUID id = domain.createOrGetSession(command).session().getId();
        var endedButUncommitted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var end = executor.submit(() -> transaction().execute(status -> {
                sessions.findByIdForUpdate(id).orElseThrow();
                var ended = domain.endSession(id, command.ownerUserId(), "Closure precondition satisfied");
                endedButUncommitted.countDown();
                try {
                    assertTrue(commit.await(15, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return ended;
            }));
            assertTrue(endedButUncommitted.await(10, TimeUnit.SECONDS));
            var online = executor.submit(() -> domain.onMediaOnline(new MediaOnlineCommand(
                    id, UUID.randomUUID(), MediaSourceType.RTMP_CONN, UUID.randomUUID(), null)));
            try {
                assertTrue(awaitBlockedQuery("%from " + SCHEMA + ".stream_sessions%for%update%"),
                        "ONLINE must wait for the session row lock held by End");
                assertFalse(online.isDone());
            } finally {
                commit.countDown();
            }
            assertEquals(StreamSessionStatus.ENDED, end.get(10, TimeUnit.SECONDS).getStatus());
            assertEquals(StreamSessionStatus.ENDED, online.get(10, TimeUnit.SECONDS).getStatus());
        }
        assertEquals(StreamSessionStatus.ENDED, sessions.findById(id).orElseThrow().getStatus());
        assertEquals(MediaPathState.CLOSED, sessions.findById(id).orElseThrow().getMediaPathState());
        assertEquals(2, history.findByStreamIdOrderByCreatedAtAscIdAsc(id).size());
    }

    private SessionCreationResult concurrentRetry(CreateSessionCommand first, CreateSessionCommand retry) throws Exception {
        var inserted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var winner = executor.submit(() -> transaction().execute(status -> {
                var result = domain.createOrGetSession(first);
                inserted.countDown();
                try {
                    assertTrue(commit.await(15, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return result;
            }));
            assertTrue(inserted.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> domain.createOrGetSession(retry));
            try {
                assertTrue(awaitBlockedQuery("%insert into " + SCHEMA + ".stream_sessions%"),
                        "Competing request must reach the unique index before winner commits");
            } finally {
                commit.countDown();
            }
            assertTrue(winner.get(10, TimeUnit.SECONDS).created());
            return second.get(10, TimeUnit.SECONDS);
        }
    }

    private boolean awaitBlockedQuery(String queryPattern) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            int waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like ?",
                    Integer.class, queryPattern);
            if (waiting > 0) {
                return true;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        return false;
    }

    private CreateSessionCommand command() {
        return new CreateSessionCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "Original metadata", UUID.randomUUID());
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
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
