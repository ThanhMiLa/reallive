package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.adapter.media.MediaJwtIssuer;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.dto.CreateSessionCommand;
import com.reallive.streamservice.stream.dto.MediaOnlineCommand;
import com.reallive.streamservice.stream.dto.response.StreamPlaybackAccessResponse;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class StreamPlaybackConcurrencyPostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();

    @Autowired private StreamPlaybackService playbackService;
    @Autowired private StreamSessionClosureService closureService;
    @Autowired private StreamSessionDomainService domain;
    @Autowired private StreamSessionRepository sessions;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private MediaServerAdapter media;
    @MockitoSpyBean private MediaJwtIssuer issuer;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        configureDatabase(registry, SCHEMA);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        dropSchema(SCHEMA);
    }

    @BeforeEach
    void prepareMediaStub() {
        when(media.getPath(any())).thenReturn(Optional.empty());
        when(media.getSource(any())).thenReturn(Optional.empty());
    }

    @Test
    void playbackWaitsForAnUncommittedEndAndThenRejectsTheEndedSession() throws Exception {
        UUID id = liveSession();
        var endHoldsLock = new CountDownLatch(1);
        var releaseEnd = new CountDownLatch(1);
        doAnswer(call -> {
            endHoldsLock.countDown();
            assertTrue(releaseEnd.await(15, TimeUnit.SECONDS));
            return null;
        }).when(media).deletePath(id);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var ending = executor.submit(() -> closureService.end(id, ownerOf(id), null));
            assertTrue(endHoldsLock.await(10, TimeUnit.SECONDS));

            var access = executor.submit(() -> playbackService.access(id, UUID.randomUUID()));
            try {
                assertTrue(awaitBlockedStreamQuery(), "Playback must wait for the row lock held by End");
                assertTrue(!access.isDone());
            } finally {
                releaseEnd.countDown();
            }

            assertEquals(StreamSessionStatus.ENDED, ending.get(10, TimeUnit.SECONDS).status());
            var failure = assertThrows(ExecutionException.class, () -> access.get(10, TimeUnit.SECONDS));
            assertEquals(ErrorCode.STREAM_SESSION_STATE_INVALID,
                    assertInstanceOf(AppException.class, failure.getCause()).getErrorCode());
        }
    }

    @Test
    void endWaitsForThePlaybackTransactionToCommitItsCredential() throws Exception {
        UUID id = liveSession();
        var playbackHoldsLock = new CountDownLatch(1);
        var releasePlayback = new CountDownLatch(1);
        doAnswer(call -> {
            playbackHoldsLock.countDown();
            assertTrue(releasePlayback.await(15, TimeUnit.SECONDS));
            return call.callRealMethod();
        }).when(issuer).issueRead(eq(id), any());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var access = executor.submit(() -> playbackService.access(id, UUID.randomUUID()));
            assertTrue(playbackHoldsLock.await(10, TimeUnit.SECONDS));

            var ending = executor.submit(() -> closureService.end(id, ownerOf(id), null));
            try {
                assertTrue(awaitBlockedStreamQuery(), "End must wait for the row lock held by playback access");
                assertTrue(!ending.isDone());
            } finally {
                releasePlayback.countDown();
            }

            StreamPlaybackAccessResponse granted = access.get(10, TimeUnit.SECONDS);
            assertNotNull(granted.readToken());
            assertEquals(StreamSessionStatus.ENDED, ending.get(10, TimeUnit.SECONDS).status());
        }

        assertEquals(StreamSessionStatus.ENDED, sessions.findById(id).orElseThrow().getStatus());
    }

    private UUID liveSession() {
        var command = new CreateSessionCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "Concurrent playback", UUID.randomUUID());
        UUID id = domain.createOrGetSession(command).session().getId();
        domain.onMediaOnline(new MediaOnlineCommand(id, UUID.randomUUID(), MediaSourceType.RTMP_CONN,
                UUID.randomUUID(), null));
        return id;
    }

    private UUID ownerOf(UUID id) {
        return sessions.findById(id).orElseThrow().getOwnerUserId();
    }

    private boolean awaitBlockedStreamQuery() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            int waiting = jdbc.queryForObject("""
                    SELECT count(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND wait_event_type = 'Lock'
                      AND query LIKE ?
                    """, Integer.class, "%stream_sessions%for%update%");
            if (waiting > 0) {
                return true;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        return false;
    }
}
