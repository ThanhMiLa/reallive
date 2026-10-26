package com.reallive.streamservice.stream.service;

import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.adapter.media.MediaPathConfiguration;
import com.reallive.streamservice.stream.adapter.media.MediaPathStatus;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.client.channel.ChannelClient;
import com.reallive.streamservice.stream.dto.MediaOfflineCommand;
import com.reallive.streamservice.stream.dto.MediaOnlineCommand;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import com.reallive.streamservice.stream.dto.request.CreateStreamSessionRequest;
import com.reallive.streamservice.stream.dto.request.UpdateStreamSessionRequest;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class StreamProvisioningPostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final Instant BASE = Instant.parse("2026-10-26T08:00:00Z");

    @Autowired private StreamSessionService service;
    @Autowired private StreamSessionClosureService closure;
    @Autowired private StreamSessionDomainService domain;
    @Autowired private StreamSessionRepository sessions;
    @Autowired private StreamStatusHistoryRepository history;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @MockitoBean private StreamEligibilityService eligibility;
    @MockitoBean private ChannelClient channels;
    @MockitoBean private MediaServerAdapter media;
    private final Set<UUID> configured = ConcurrentHashMap.newKeySet();

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
        sessions.deleteAll();
        clock.now = BASE;
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            configured.add(call.getArgument(0));
            return null;
        }).when(media).ensurePath(any());
        when(media.getPath(any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            return configured.contains(id) ? Optional.of(new MediaPathConfiguration("live/" + id, "publisher", false)) : Optional.empty();
        });
        doAnswer(call -> { configured.remove(call.getArgument(0)); return null; }).when(media).deletePath(any());
    }

    @Test
    void createsCommittedSessionAndReplayPreservesMetadataWithoutProvisioningAgain() throws Exception {
        var owner = owner();
        var request = request(owner);
        var created = service.create(owner.user(), request);
        var session = created.response().session();
        assertTrue(created.created());
        assertEquals(StreamSessionStatus.READY, session.status());
        assertTrue(session.publishReady());
        assertTrue(sessions.findById(session.id()).isPresent());
        assertEquals(1, history.findByStreamIdOrderByCreatedAtAscIdAsc(session.id()).size());
        var claims = SignedJWT.parse(created.response().publishCredentials().streamKey().split("token=")[1]).getJWTClaimsSet();
        assertEquals(List.of(Map.of("action", "publish", "path", "live/" + session.id())), claims.getClaim("mediamtx_permissions"));
        assertEquals(BASE.plus(Duration.ofHours(8)), created.response().publishCredentials().publishExpiresAt());
        var retry = service.create(owner.user(), new CreateStreamSessionRequest(request.requestId(), "Must not overwrite", UUID.randomUUID()));
        assertFalse(retry.created());
        assertEquals(session, retry.response().session());
        verify(media, times(1)).ensurePath(session.id());
    }

    @Test
    void mediaFailureRollsBackSlotAndHistoryAndUserCanRetrySameRequest() {
        var owner = owner();
        var request = request(owner);
        var attempted = new AtomicReference<UUID>();
        doAnswer(call -> {
            attempted.set(call.getArgument(0));
            throw new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
        }).when(media).ensurePath(any());
        error(ErrorCode.MEDIA_SERVER_UNAVAILABLE, () -> service.create(owner.user(), request));
        assertEquals(0, sessions.count());
        assertEquals(0, history.count());
        assertTrue(sessions.findSlotOccupyingByChannelId(owner.channel()).isEmpty());
        doAnswer(call -> { configured.add(call.getArgument(0)); return null; }).when(media).ensurePath(any());
        var created = service.create(owner.user(), request);
        assertTrue(created.created());
        assertNotEquals(attempted.get(), created.response().session().id());
    }

    @Test
    void eligibilityFailuresNeverReserveOrTouchMedia() {
        var owner = owner();
        when(eligibility.requirePublishContext(eq(owner.user()), any())).thenThrow(new AppException(ErrorCode.STREAMER_NOT_ELIGIBLE));
        error(ErrorCode.STREAMER_NOT_ELIGIBLE, () -> service.create(owner.user(), request(owner)));
        assertEquals(0, sessions.count());
        verifyNoInteractions(media);
    }

    @Test
    void responseWaitsForMediaAndUncommittedReservationIsInvisible() throws Exception {
        var owner = owner();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            configured.add(call.getArgument(0));
            return null;
        }).when(media).ensurePath(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var response = executor.submit(() -> service.create(owner.user(), request(owner)));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> response.get(150, TimeUnit.MILLISECONDS));
                assertEquals(0, jdbc.queryForObject("select count(*) from stream_sessions", Integer.class));
                assertEquals(0, jdbc.queryForObject("select count(*) from stream_status_history", Integer.class));
            } finally {
                release.countDown();
            }
            assertTrue(response.get(5, TimeUnit.SECONDS).created());
        }
    }

    @Test
    void simultaneousSameRequestReturnsOneSessionAndOneHistoryAfterConflictRollback() throws Exception {
        concurrentCreate(true);
    }

    @Test
    void simultaneousDifferentRequestsHaveOneWinnerAndReturnConflictForTheOther() throws Exception {
        concurrentCreate(false);
    }

    @Test
    void currentRecoversLostCreateResponseAndCredentialsCanBeReissuedWithoutRevocation() {
        var owner = owner();
        assertNull(service.current(owner.user()));
        var created = service.create(owner.user(), request(owner));
        var id = created.response().session().id();
        assertEquals(id, service.current(owner.user()).id());
        var fresh = service.publishCredentials(id, owner.user());
        assertNotEquals(created.response().publishCredentials().streamKey(), fresh.streamKey());
        assertTrue(fresh.streamKey().startsWith(id + "?token="));
        assertFalse(service.current(owner.user()).toString().contains("token="));
        configured.remove(id);
        assertFalse(service.current(owner.user()).publishReady());
        error(ErrorCode.MEDIA_SERVER_UNAVAILABLE, () -> service.publishCredentials(id, owner.user()));
        verify(media, times(1)).ensurePath(id);
    }

    @Test
    void updateChangesOnlySuppliedFieldsWithoutMediaIoAndInvalidCategoryRollsBackEverything() {
        var owner = owner();
        var id = service.create(owner.user(), request(owner)).response().session().id();
        clearInvocations(media);
        var title = new UpdateStreamSessionRequest();
        title.setTitle("  New title  ");
        var updated = service.update(id, owner.user(), title);
        assertEquals("New title", updated.title());
        assertEquals(owner.category(), updated.categoryId());
        assertEquals("/live/" + id + "/index.m3u8", sessions.findById(id).orElseThrow().getPlaybackPath());
        verifyNoInteractions(media);
        UUID invalid = UUID.randomUUID();
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            throw new AppException(ErrorCode.STREAM_CATEGORY_INVALID);
        }).when(channels).requireCategory(invalid);
        var both = new UpdateStreamSessionRequest();
        both.setTitle("Must not persist");
        both.setCategoryId(invalid);
        error(ErrorCode.STREAM_CATEGORY_INVALID, () -> service.update(id, owner.user(), both));
        assertEquals("New title", sessions.findById(id).orElseThrow().getTitle());
        assertEquals(owner.category(), sessions.findById(id).orElseThrow().getCategoryId());
    }

    @Test
    void anotherOwnerCannotUpdateOrObtainCredentialsAndEndedSessionCannotBeReopened() {
        var owner = owner();
        var request = request(owner);
        var id = service.create(owner.user(), request).response().session().id();
        clearInvocations(media);
        var patch = new UpdateStreamSessionRequest();
        patch.setTitle("Intrusion");
        error(ErrorCode.STREAM_SESSION_NOT_OWNED, () -> service.update(id, UUID.randomUUID(), patch));
        error(ErrorCode.STREAM_SESSION_NOT_OWNED, () -> service.publishCredentials(id, UUID.randomUUID()));
        verifyNoInteractions(media);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            sessions.findByIdForUpdate(id).orElseThrow();
            domain.endSession(id, owner.user(), "Test confirmed closure");
        });
        error(ErrorCode.STREAM_SESSION_STATE_INVALID, () -> service.update(id, owner.user(), patch));
        error(ErrorCode.STREAM_SESSION_STATE_INVALID, () -> service.publishCredentials(id, owner.user()));
        error(ErrorCode.STREAM_SESSION_STATE_INVALID, () -> service.create(owner.user(), request));
        assertNull(service.current(owner.user()));
        verifyNoInteractions(media);
    }

    @Test
    void persistedReconnectDeadlineBlocksCredentialsAndReadinessAtTheBoundary() {
        var owner = owner();
        var id = service.create(owner.user(), request(owner)).response().session().id();
        var source = UUID.randomUUID();
        domain.onMediaOnline(new MediaOnlineCommand(id, source, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        domain.onMediaOffline(new MediaOfflineCommand(id, source, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
        clock.now = BASE.plusSeconds(59);
        assertTrue(service.current(owner.user()).publishReady());
        assertNotNull(service.publishCredentials(id, owner.user()));
        clock.now = BASE.plusSeconds(60);
        assertFalse(service.current(owner.user()).publishReady());
        assertEquals(BASE.plusSeconds(60), service.current(owner.user()).reconnectDeadlineAt());
        error(ErrorCode.STREAM_SESSION_STATE_INVALID, () -> service.publishCredentials(id, owner.user()));
        error(ErrorCode.STREAM_SESSION_ALREADY_ACTIVE, () -> service.create(owner.user(), request(owner)));
    }

    @Test
    void synchronousClosureRollsBackOnMediaFailureThenCommitsAndIsIdempotent() {
        var owner = owner();
        var id = service.create(owner.user(), request(owner)).response().session().id();
        error(ErrorCode.STREAM_SESSION_NOT_OWNED, () -> closure.end(id, UUID.randomUUID(), null));
        verify(media, never()).deletePath(any());
        doThrow(new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE)).when(media).deletePath(id);
        error(ErrorCode.MEDIA_SERVER_UNAVAILABLE, () -> closure.end(id, owner.user(), null));
        assertEquals(StreamSessionStatus.READY, sessions.findById(id).orElseThrow().getStatus());
        assertEquals(1, history.count());
        error(ErrorCode.STREAM_SESSION_ALREADY_ACTIVE, () -> service.create(owner.user(), request(owner)));
        doAnswer(call -> { configured.remove(id); return null; }).when(media).deletePath(id);
        assertEquals(StreamSessionStatus.ENDED, closure.end(id, owner.user(), null).status());
        clearInvocations(media);
        closure.end(id, owner.user(), null);
        verifyNoInteractions(media);
        assertEquals(2, history.count());
        assertTrue(service.create(owner.user(), request(owner)).created());
    }

    @Test
    void createCannotTakeChannelSlotUntilEndConfirmsClosureAndCommits() throws Exception {
        var owner = owner();
        var id = service.create(owner.user(), request(owner)).response().session().id();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            configured.remove(id);
            return null;
        }).when(media).deletePath(id);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var ending = executor.submit(() -> closure.end(id, owner.user(), null));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var next = executor.submit(() -> service.create(owner.user(), request(owner)));
            try {
                assertThrows(TimeoutException.class, () -> next.get(150, TimeUnit.MILLISECONDS));
                assertEquals(StreamSessionStatus.READY, sessions.findById(id).orElseThrow().getStatus());
            } finally {
                release.countDown();
            }
            assertEquals(StreamSessionStatus.ENDED, ending.get(5, TimeUnit.SECONDS).status());
            assertTrue(next.get(5, TimeUnit.SECONDS).created());
        }
    }

    @Test
    void credentialRequestWaitsForEndThenRechecksTerminalStateUnderTheLock() throws Exception {
        var owner = owner();
        var id = service.create(owner.user(), request(owner)).response().session().id();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            configured.remove(id);
            return null;
        }).when(media).deletePath(id);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var ending = executor.submit(() -> closure.end(id, owner.user(), null));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var credential = executor.submit(() -> service.publishCredentials(id, owner.user()));
            try {
                assertThrows(TimeoutException.class, () -> credential.get(150, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            ending.get(5, TimeUnit.SECONDS);
            var failure = assertThrows(ExecutionException.class, () -> credential.get(5, TimeUnit.SECONDS));
            assertEquals(ErrorCode.STREAM_SESSION_STATE_INVALID, assertInstanceOf(AppException.class, failure.getCause()).getErrorCode());
        }
    }

    @Test
    void closureInspectsTheActualSourceAndKicksItsConnectionIdWithTheCorrectProtocol() {
        var owner = owner();
        var id = service.create(owner.user(), request(owner)).response().session().id();
        UUID actualConnection = UUID.randomUUID();
        when(media.getSource(id)).thenReturn(Optional.of(new MediaPathStatus("live/" + id, true,
                actualConnection, MediaSourceType.RTMPS_CONN)), Optional.empty());
        closure.end(id, owner.user(), null);
        verify(media).kick(MediaSourceType.RTMPS_CONN, actualConnection);
        verify(media, never()).kick(any(), eq(id));
        assertEquals(StreamSessionStatus.ENDED, sessions.findById(id).orElseThrow().getStatus());
    }

    private void concurrentCreate(boolean sameRequest) throws Exception {
        var owner = owner();
        var first = request(owner);
        var second = new CreateStreamSessionRequest(sameRequest ? first.requestId() : UUID.randomUUID(), "Other metadata", UUID.randomUUID());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            configured.add(call.getArgument(0));
            return null;
        }).when(media).ensurePath(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var winner = executor.submit(() -> service.create(owner.user(), first));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var loser = executor.submit(() -> service.create(owner.user(), second));
            try {
                assertThrows(TimeoutException.class, () -> loser.get(150, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            var result = winner.get(5, TimeUnit.SECONDS);
            if (sameRequest) {
                var replay = loser.get(5, TimeUnit.SECONDS);
                assertFalse(replay.created());
                assertEquals(result.response().session(), replay.response().session());
            } else {
                var failure = assertThrows(ExecutionException.class, () -> loser.get(5, TimeUnit.SECONDS));
                assertEquals(ErrorCode.STREAM_SESSION_ALREADY_ACTIVE, assertInstanceOf(AppException.class, failure.getCause()).getErrorCode());
            }
            assertEquals(1, sessions.count());
            assertEquals(1, history.count());
            verify(media, times(1)).ensurePath(any());
        }
    }

    private Owner owner() {
        var owner = new Owner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(eligibility.requirePublishContext(eq(owner.user()), any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Remote eligibility must precede write transaction");
            return new StreamPublishContext(owner.user(), owner.streamer(), owner.channel(), call.getArgument(1));
        });
        return owner;
    }

    private CreateStreamSessionRequest request(Owner owner) {
        return new CreateStreamSessionRequest(UUID.randomUUID(), "Test stream", owner.category());
    }

    private void error(ErrorCode code, Executable action) {
        assertEquals(code, assertThrows(AppException.class, action).getErrorCode());
    }

    private record Owner(
            UUID user,
            UUID streamer,
            UUID channel,
            UUID category
    ) {}

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
