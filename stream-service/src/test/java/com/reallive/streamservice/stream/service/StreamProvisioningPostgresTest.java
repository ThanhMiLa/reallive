package com.reallive.streamservice.stream.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.adapter.media.dto.MediaPathConfiguration;
import com.reallive.streamservice.stream.adapter.media.dto.MediaPathStatus;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.dto.MediaEventOutcome;
import com.reallive.streamservice.stream.dto.MediaIngestEventCommand;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import com.reallive.streamservice.stream.dto.request.SaveStreamSetupRequest;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class StreamProvisioningPostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final Instant BASE = Instant.parse("2026-10-26T08:00:00Z");

    @Autowired private StreamSetupService setupService;
    @Autowired private StreamSessionService sessionService;
    @Autowired private StreamMediaLifecycleService lifecycleService;
    @Autowired private ChannelStreamConfigRepository configs;
    @Autowired private StreamSessionRepository sessions;
    @Autowired private StreamStatusHistoryRepository history;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @MockitoBean private StreamEligibilityService eligibility;
    @MockitoBean private MediaServerAdapter media;

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
    void defaults() {
        history.deleteAll();
        sessions.deleteAll();
        configs.deleteAll();
        clock.now = BASE;
        configuredIngests.clear();
        activePlaybacks.clear();
        activeIngestSources.clear();

        doAnswer(
                        call -> {
                            configuredIngests.add(call.getArgument(0));
                            return null;
                        })
                .when(media)
                .ensureIngestPath(any());

        doAnswer(
                        call -> {
                            UUID id = call.getArgument(0);
                            configuredIngests.remove(id);
                            activeIngestSources.remove(id);
                            return null;
                        })
                .when(media)
                .deleteIngestPath(any());

        doAnswer(
                        call -> {
                            UUID sourceId = call.getArgument(1);
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
                        call -> {
                            UUID id = call.getArgument(0);
                            if (!configuredIngests.contains(id)) {
                                return Optional.empty();
                            }
                            return Optional.of(
                                    new MediaPathConfiguration("ingest/" + id, "publisher", false));
                        });
        when(media.getIngestSource(any()))
                .thenAnswer(
                        call -> Optional.ofNullable(activeIngestSources.get(call.getArgument(0))));
        doAnswer(
                        call -> {
                            activePlaybacks.add(call.getArgument(0));
                            return null;
                        })
                .when(media)
                .ensurePlaybackPath(any(), any(), any());
        doAnswer(
                        call -> {
                            activePlaybacks.remove(call.getArgument(0));
                            return null;
                        })
                .when(media)
                .deletePlaybackPath(any());
        when(media.getPlaybackPath(any()))
                .thenAnswer(
                        call -> {
                            UUID id = call.getArgument(0);
                            return activePlaybacks.contains(id)
                                    ? Optional.of(
                                            new MediaPathConfiguration(
                                                    "live/" + id, "rtmp://127.0.0.1/...", false))
                                    : Optional.empty();
                        });
        when(media.getPlaybackSource(any()))
                .thenAnswer(
                        call -> {
                            UUID id = call.getArgument(0);
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
    void settingsNeverIssueOrExposeCredentials() {
        Owner owner = owner();
        var draft = setupService.getOrCreateSetup(owner.user());
        assertFalse(draft.hasStreamKey());
        var saved =
                setupService.saveSetup(
                        owner.user(), new SaveStreamSetupRequest("My title", owner.category()));
        assertFalse(saved.hasStreamKey());
        verify(media, never()).ensureIngestPath(any());
        assertEquals(0, sessions.count());
    }

    @Test
    void keyIsReturnedOnlyByIssuanceAndMetadataContainsNoTokens() throws Exception {
        Owner owner = configuredOwner();
        var issued = setupService.issueStreamKey(owner.user());
        var token = SignedJWT.parse(issued.streamKey().split("\\?token=", 2)[1]);
        assertNull(token.getJWTClaimsSet().getExpirationTime());
        assertTrue(setupService.getOrCreateSetup(owner.user()).hasStreamKey());
        assertFalse(issued.toString().contains(issued.streamKey()));
        assertEquals(0, sessions.count());
        assertEquals(
                List.of("channel_stream_configs", "stream_sessions", "stream_status_history"),
                jdbc.queryForList(
                        "SELECT table_name FROM information_schema.tables WHERE"
                                + " table_schema=current_schema() AND table_name <>"
                                + " 'flyway_schema_history' ORDER BY table_name",
                        String.class));
    }

    @Test
    void newKeyRetiresOldPathAndEndsLiveSession() {
        Owner owner = broadcastingOwner();
        UUID oldIngest = ingest(owner);
        UUID sessionId = current(owner);
        var replacement = setupService.issueStreamKey(owner.user());
        assertFalse(configuredIngests.contains(oldIngest));
        assertTrue(configuredIngests.contains(ingest(owner)));
        assertNotEquals(oldIngest, ingest(owner));
        assertTrue(replacement.streamKey().startsWith(ingest(owner).toString()));
        assertEquals(
                StreamSessionStatus.ENDED, sessions.findById(sessionId).orElseThrow().getStatus());
        assertFalse(activePlaybacks.contains(sessionId));
    }

    @Test
    void savePreservesKeyAndUpdatesLiveMetadata() {
        Owner owner = broadcastingOwner();
        UUID before = ingest(owner);
        UUID sessionId = current(owner);
        setupService.saveSetup(
                owner.user(), new SaveStreamSetupRequest("Changed", owner.category()));
        assertEquals(before, ingest(owner));
        assertEquals("Changed", sessions.findById(sessionId).orElseThrow().getTitle());
    }

    @Test
    void obsStartCreatesLiveAndDuplicateOnlineIsIdempotent() {
        Owner owner = configuredOwner();
        setupService.issueStreamKey(owner.user());
        UUID source = UUID.randomUUID();
        publish(owner, source);
        var event = event(owner, source);
        lifecycleService.onOnline(event);
        UUID first = current(owner);
        lifecycleService.onOnline(event);
        lifecycleService.onOnline(event(owner, source));
        assertEquals(first, current(owner));
        assertEquals(1, sessions.count());
        assertEquals(1, history.count());
        assertTrue(activePlaybacks.contains(first));
    }

    @Test
    void reconnectBeforeDeadlineKeepsSessionAndLateOfflineCannotCloseIt() {
        Owner owner = broadcastingOwner();
        UUID id = current(owner);
        UUID oldSource = activeIngestSources.get(ingest(owner)).sourceId();
        disconnect(owner, oldSource);
        clock.now = clock.now.plusSeconds(59);
        UUID newSource = UUID.randomUUID();
        publish(owner, newSource);
        lifecycleService.onOnline(event(owner, newSource));
        assertEquals(id, current(owner));
        lifecycleService.onOffline(event(owner, oldSource));
        assertEquals(StreamSessionStatus.LIVE, sessions.findById(id).orElseThrow().getStatus());
        assertTrue(activePlaybacks.contains(id));
    }

    @Test
    void reconnectAtDeadlineCreatesNewSessionEvenBeforeSchedulerRuns() {
        Owner owner = broadcastingOwner();
        UUID old = current(owner);
        disconnect(owner, activeIngestSources.get(ingest(owner)).sourceId());
        clock.now = clock.now.plusSeconds(60);
        UUID source = UUID.randomUUID();
        publish(owner, source);
        lifecycleService.onOnline(event(owner, source));
        assertNotEquals(old, current(owner));
        assertEquals(StreamSessionStatus.ENDED, sessions.findById(old).orElseThrow().getStatus());
    }

    @Test
    void graceExpirationKeepsIngestForNextBroadcast() {
        Owner owner = broadcastingOwner();
        UUID id = current(owner);
        UUID ingest = ingest(owner);
        disconnect(owner, activeIngestSources.get(ingest).sourceId());
        clock.now = clock.now.plusSeconds(59);
        assertFalse(closure.expireReconnectIfDue(id));
        clock.now = clock.now.plusSeconds(1);
        assertTrue(closure.expireReconnectIfDue(id));
        assertTrue(configuredIngests.contains(ingest));
        assertEquals(StreamSessionStatus.ENDED, sessions.findById(id).orElseThrow().getStatus());
    }

    @Test
    void unknownDisconnectDoesNotMergeDifferentPublishers() {
        Owner owner = broadcastingOwner();
        UUID old = current(owner);
        UUID next = UUID.randomUUID();
        publish(owner, next);
        lifecycleService.onOnline(event(owner, next));
        assertNotEquals(old, current(owner));
    }

    @Test
    void callbackForRetiredKeyIsIgnored() {
        Owner owner = broadcastingOwner();
        var stale = event(owner, activeIngestSources.get(ingest(owner)).sourceId());
        setupService.issueStreamKey(owner.user());
        assertEquals(MediaEventOutcome.IGNORED, lifecycleService.onOnline(stale).outcome());
        assertTrue(sessions.findSlotOccupyingByChannelId(owner.channel()).isEmpty());
    }

    @Test
    void failedMediaCallReturnsErrorWithoutSchedulingBackgroundWork() {
        Owner owner = configuredOwner();
        doThrow(new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE))
                .when(media)
                .ensureIngestPath(any());
        error(ErrorCode.MEDIA_SERVER_UNAVAILABLE, () -> setupService.issueStreamKey(owner.user()));
        assertNull(ingest(owner));
    }

    @Test
    void concurrentFirstGetCreatesSingleConfig() throws Exception {
        Owner owner = owner();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> setupService.getOrCreateSetup(owner.user()));
            var second = executor.submit(() -> setupService.getOrCreateSetup(owner.user()));
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, configs.count());
    }

    @Test
    void concurrentOnlineCallbacksCreateOnlyOneSession() throws Exception {
        Owner owner = configuredOwner();
        setupService.issueStreamKey(owner.user());
        UUID source = UUID.randomUUID();
        publish(owner, source);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> lifecycleService.onOnline(event(owner, source)));
            var second = executor.submit(() -> lifecycleService.onOnline(event(owner, source)));
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, sessions.count());
    }

    @Test
    void concurrentKeyRequestsLeaveOnlyTheLatestIngestWhitelisted() throws Exception {
        Owner owner = configuredOwner();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> setupService.issueStreamKey(owner.user()));
            var second = executor.submit(() -> setupService.issueStreamKey(owner.user()));
            assertNotEquals(first.get(5, TimeUnit.SECONDS).streamKey(), second.get(5, TimeUnit.SECONDS).streamKey());
        }
        assertEquals(Set.of(ingest(owner)), configuredIngests);
    }

    @Test
    void repeatedOfflineDoesNotExtendReconnectDeadline() {
        Owner owner = broadcastingOwner();
        UUID source = activeIngestSources.get(ingest(owner)).sourceId();
        UUID sessionId = current(owner);
        disconnect(owner, source);
        var deadline = sessions.findById(sessionId).orElseThrow().getReconnectDeadlineAt();
        clock.now = clock.now.plusSeconds(20);
        lifecycleService.onOffline(event(owner, source));
        assertEquals(deadline, sessions.findById(sessionId).orElseThrow().getReconnectDeadlineAt());
    }

    @Autowired private StreamSessionClosureService closure;

    private Owner configuredOwner() {
        Owner owner = owner();
        setupService.saveSetup(
                owner.user(), new SaveStreamSetupRequest("My broadcast", owner.category()));
        return owner;
    }

    private Owner broadcastingOwner() {
        Owner owner = configuredOwner();
        setupService.issueStreamKey(owner.user());
        UUID source = UUID.randomUUID();
        publish(owner, source);
        lifecycleService.onOnline(event(owner, source));
        return owner;
    }

    private UUID ingest(Owner owner) {
        return configs.findById(owner.channel()).orElseThrow().getCurrentIngestKeyId();
    }

    private UUID current(Owner owner) {
        return sessions.findSlotOccupyingByChannelId(owner.channel()).orElseThrow().getId();
    }

    private void publish(Owner owner, UUID source) {
        activeIngestSources.put(
                ingest(owner),
                new MediaPathStatus(
                        "ingest/" + ingest(owner), true, source, MediaSourceType.RTMP_CONN));
    }

    private MediaIngestEventCommand event(Owner owner, UUID source) {
        return new MediaIngestEventCommand(
                ingest(owner),
                source,
                MediaSourceType.RTMP_CONN,
                UUID.randomUUID(),
                clock.now.atZone(ZoneOffset.UTC).toLocalDateTime());
    }

    private void disconnect(Owner owner, UUID source) {
        activeIngestSources.remove(ingest(owner));
        lifecycleService.onOffline(event(owner, source));
    }

    private Owner owner() {
        Owner owner =
                new Owner(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(eligibility.requireChannelContext(owner.user()))
                .thenReturn(
                        new StreamPublishContext(
                                owner.user(), owner.streamer(), owner.channel(), null));
        when(eligibility.requirePublishContext(eq(owner.user()), any()))
                .thenAnswer(
                        call -> {
                            assertFalse(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            return new StreamPublishContext(
                                    owner.user(),
                                    owner.streamer(),
                                    owner.channel(),
                                    call.getArgument(1));
                        });
        return owner;
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
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
