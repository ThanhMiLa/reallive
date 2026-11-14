package com.reallive.streamservice.stream.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.reallive.streamservice.client.channel.ChannelClient;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.adapter.media.MediaJwtIssuer;
import com.reallive.streamservice.stream.adapter.media.dto.MediaPathConfiguration;
import com.reallive.streamservice.stream.adapter.media.dto.MediaPathStatus;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.dto.MediaIngestEventCommand;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import com.reallive.streamservice.stream.dto.request.SaveStreamSetupRequest;
import com.reallive.streamservice.stream.dto.response.StreamPlaybackAccessResponse;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.repository.StreamStatusHistoryRepository;

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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@SpringBootTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class StreamPlaybackConcurrencyPostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();

    @Autowired private StreamSetupService setupService;
    @Autowired private StreamPlaybackService playbackService;
    @Autowired private StreamMediaLifecycleService lifecycleService;
    @Autowired private StreamSessionRepository sessions;
    @Autowired private StreamStatusHistoryRepository history;
    @Autowired private ChannelStreamConfigRepository configs;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private StreamEligibilityService eligibility;
    @MockitoBean private MediaServerAdapter media;
    @MockitoSpyBean private ChannelClient channelAccess;
    @MockitoSpyBean private MediaJwtIssuer issuer;
    @MockitoSpyBean private StreamViewerPresenceService viewerPresenceSpy;

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
    void prepareMediaStub() {
        history.deleteAll();
        sessions.deleteAll();
        configs.deleteAll();
        configuredIngests.clear();
        activePlaybacks.clear();
        activeIngestSources.clear();
        doNothing().when(channelAccess).requireViewAccess(any(), any());
        doAnswer(
                        inv -> {
                            configuredIngests.add(inv.getArgument(0));
                            return null;
                        })
                .when(media)
                .ensureIngestPath(any());
        doAnswer(
                        inv -> {
                            UUID id = inv.getArgument(0);
                            configuredIngests.remove(id);
                            activeIngestSources.remove(id);
                            return null;
                        })
                .when(media)
                .deleteIngestPath(any());
        doAnswer(
                        inv -> {
                            UUID sourceId = inv.getArgument(1);
                            activeIngestSources
                                    .entrySet()
                                    .removeIf(
                                            entry -> sourceId.equals(entry.getValue().sourceId()));
                            return null;
                        })
                .when(media)
                .kick(any(), any());
        when(media.getIngestPath(any()))
                .thenAnswer(
                        inv -> {
                            UUID id = inv.getArgument(0);
                            return configuredIngests.contains(id)
                                    ? Optional.of(
                                            new MediaPathConfiguration(
                                                    "ingest/" + id, "publisher", false))
                                    : Optional.empty();
                        });
        when(media.getIngestSource(any()))
                .thenAnswer(
                        inv -> Optional.ofNullable(activeIngestSources.get(inv.getArgument(0))));
        doAnswer(
                        inv -> {
                            activePlaybacks.add(inv.getArgument(0));
                            return null;
                        })
                .when(media)
                .ensurePlaybackPath(any(), any(), any());
        doAnswer(
                        inv -> {
                            activePlaybacks.remove(inv.getArgument(0));
                            return null;
                        })
                .when(media)
                .deletePlaybackPath(any());
        when(media.getPlaybackPath(any()))
                .thenAnswer(
                        inv -> {
                            UUID id = inv.getArgument(0);
                            return activePlaybacks.contains(id)
                                    ? Optional.of(
                                            new MediaPathConfiguration(
                                                    "live/" + id, "rtmp://127.0.0.1/...", false))
                                    : Optional.empty();
                        });
        when(media.getPlaybackSource(any()))
                .thenAnswer(
                        inv -> {
                            UUID id = inv.getArgument(0);
                            return activePlaybacks.contains(id)
                                    ? Optional.of(
                                            new MediaPathStatus(
                                                    "live/" + id,
                                                    true,
                                                    UUID.randomUUID(),
                                                    MediaSourceType.RTMP_SOURCE))
                                    : Optional.empty();
                        });
    }

    @Test
    void playbackWaitsForUncommittedResetEndAndThenRejectsEndedSession() throws Exception {
        LiveFixture fixture = liveSession();
        UUID sessionId = fixture.sessionId();

        var commitHoldsLock = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            commitHoldsLock.countDown();
                            assertTrue(releaseCommit.await(15, TimeUnit.SECONDS));
                            call.callRealMethod();
                            return null;
                        })
                .when(viewerPresenceSpy)
                .closeAfterCommit(sessionId);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var resetting =
                    executor.submit(() -> setupService.issueStreamKey(fixture.ownerUserId()));
            assertTrue(commitHoldsLock.await(10, TimeUnit.SECONDS));

            var access =
                    executor.submit(() -> playbackService.access(sessionId, UUID.randomUUID()));
            try {
                assertTrue(
                        awaitBlockedStreamQuery(),
                        "Playback must wait for row lock held by RESET_KEY commit");
                assertTrue(!access.isDone());
            } finally {
                releaseCommit.countDown();
            }

            assertNotNull(resetting.get(10, TimeUnit.SECONDS).streamKey());
            var failure =
                    assertThrows(ExecutionException.class, () -> access.get(10, TimeUnit.SECONDS));
            assertEquals(
                    ErrorCode.STREAM_SESSION_STATE_INVALID,
                    assertInstanceOf(AppException.class, failure.getCause()).getErrorCode());
        }

        assertEquals(
                StreamSessionStatus.ENDED, sessions.findById(sessionId).orElseThrow().getStatus());
    }

    @Test
    void resetCommitWaitsForPlaybackTransactionToCommitItsCredential() throws Exception {
        LiveFixture fixture = liveSession();
        UUID sessionId = fixture.sessionId();

        var playbackHoldsLock = new CountDownLatch(1);
        var releasePlayback = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            playbackHoldsLock.countDown();
                            assertTrue(releasePlayback.await(15, TimeUnit.SECONDS));
                            return call.callRealMethod();
                        })
                .when(issuer)
                .issueRead(eq(sessionId), any());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var access =
                    executor.submit(() -> playbackService.access(sessionId, UUID.randomUUID()));
            assertTrue(playbackHoldsLock.await(10, TimeUnit.SECONDS));

            var resetting =
                    executor.submit(() -> setupService.issueStreamKey(fixture.ownerUserId()));
            try {
                assertTrue(
                        awaitBlockedStreamQuery(),
                        "RESET_KEY commit must wait for row lock held by playback access");
                assertTrue(!resetting.isDone());
            } finally {
                releasePlayback.countDown();
            }

            StreamPlaybackAccessResponse granted = access.get(10, TimeUnit.SECONDS);
            assertNotNull(granted.readToken());
            assertNotNull(resetting.get(10, TimeUnit.SECONDS).streamKey());
        }

        assertEquals(
                StreamSessionStatus.ENDED, sessions.findById(sessionId).orElseThrow().getStatus());
    }

    private LiveFixture liveSession() {
        UUID ownerUserId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(eligibility.requireChannelContext(ownerUserId))
                .thenReturn(new StreamPublishContext(ownerUserId, streamerId, channelId, null));
        when(eligibility.requirePublishContext(ownerUserId, categoryId))
                .thenReturn(
                        new StreamPublishContext(ownerUserId, streamerId, channelId, categoryId));

        setupService.saveSetup(
                ownerUserId, new SaveStreamSetupRequest("Concurrent playback", categoryId));
        setupService.issueStreamKey(ownerUserId);

        UUID ingestKeyId = configs.findById(channelId).orElseThrow().getCurrentIngestKeyId();
        UUID sourceId = UUID.randomUUID();
        activeIngestSources.put(
                ingestKeyId,
                new MediaPathStatus(
                        "ingest/" + ingestKeyId, true, sourceId, MediaSourceType.RTMP_CONN));

        lifecycleService.onOnline(
                new MediaIngestEventCommand(
                        ingestKeyId, sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));

        UUID sessionId = sessions.findSlotOccupyingByChannelId(channelId).orElseThrow().getId();
        return new LiveFixture(ownerUserId, channelId, sessionId);
    }

    private boolean awaitBlockedStreamQuery() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            int waiting =
                    jdbc.queryForObject(
                            """
                    SELECT count(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND wait_event_type = 'Lock'
                      AND query LIKE ?
                    """,
                            Integer.class,
                            "%stream_sessions%for%update%");
            if (waiting > 0) {
                return true;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        return false;
    }

    private record LiveFixture(
        UUID ownerUserId,
        UUID channelId,
        UUID sessionId
) {}
}
