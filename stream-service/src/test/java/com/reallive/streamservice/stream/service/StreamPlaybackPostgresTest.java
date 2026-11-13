package com.reallive.streamservice.stream.service;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.client.channel.ChannelClient;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.MediaTestSupport;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.adapter.media.MediaJwtIssuer;
import com.reallive.streamservice.stream.adapter.media.MediaPathConfiguration;
import com.reallive.streamservice.stream.adapter.media.MediaPathStatus;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.dto.MediaIngestEventCommand;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import com.reallive.streamservice.stream.dto.request.SaveStreamSetupRequest;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
import com.reallive.streamservice.stream.repository.ChannelStreamCredentialRepository;
import com.reallive.streamservice.stream.repository.StreamMediaOperationRepository;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.interfaces.RSAPublicKey;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class StreamPlaybackPostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final Instant BASE = Instant.parse("2026-10-29T03:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private StreamSetupService setupService;
    @Autowired private StreamSessionService sessionService;
    @Autowired private StreamPlaybackService playbackService;
    @Autowired private StreamMediaLifecycleService lifecycleService;
    @Autowired private StreamMediaOperationWorker operationWorker;
    @Autowired private StreamSessionClosureService closureService;
    @Autowired private StreamSessionRepository sessions;
    @Autowired private StreamStatusHistoryRepository history;
    @Autowired private StreamMediaOperationRepository operations;
    @Autowired private ChannelStreamCredentialRepository credentials;
    @Autowired private ChannelStreamConfigRepository configs;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MutableClock clock;
    @MockitoBean private StreamEligibilityService eligibility;
    @MockitoBean private MediaServerAdapter media;
    @MockitoSpyBean private ChannelClient channelAccess;
    @MockitoSpyBean private MediaJwtIssuer issuer;

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
    void prepareStubs() {
        history.deleteAll();
        operations.deleteAll();
        sessions.deleteAll();
        credentials.deleteAll();
        configs.deleteAll();
        clock.now = BASE;
        configuredIngests.clear();
        activePlaybacks.clear();
        activeIngestSources.clear();
        doNothing().when(channelAccess).requireViewAccess(any(), any());
        doAnswer(inv -> {
            configuredIngests.add(inv.getArgument(0));
            return null;
        }).when(media).ensureIngestPath(any());
        doAnswer(inv -> {
            UUID id = inv.getArgument(0);
            configuredIngests.remove(id);
            activeIngestSources.remove(id);
            return null;
        }).when(media).deleteIngestPath(any());
        when(media.getIngestPath(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return configuredIngests.contains(id)
                    ? Optional.of(new MediaPathConfiguration("ingest/" + id, "publisher", false))
                    : Optional.empty();
        });
        when(media.getIngestSource(any())).thenAnswer(inv ->
                Optional.ofNullable(activeIngestSources.get(inv.getArgument(0))));
        doAnswer(inv -> {
            activePlaybacks.add(inv.getArgument(0));
            return null;
        }).when(media).ensurePlaybackPath(any(), any(), any());
        doAnswer(inv -> {
            activePlaybacks.remove(inv.getArgument(0));
            return null;
        }).when(media).deletePlaybackPath(any());
        when(media.getPlaybackPath(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return activePlaybacks.contains(id)
                    ? Optional.of(new MediaPathConfiguration("live/" + id, "rtmp://127.0.0.1/...", false))
                    : Optional.empty();
        });
        when(media.getPlaybackSource(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return activePlaybacks.contains(id)
                    ? Optional.of(new MediaPathStatus("live/" + id, true, UUID.randomUUID(), MediaSourceType.RTMP_SOURCE))
                    : Optional.empty();
        });
    }

    @Test
    void internalChatContextPermitsAnonymousAccessAndReturnsCurrentLiveState() throws Exception {
        UUID id = createLiveSession().sessionId();
        String path = "/internal/api/streams/" + id + "/chat-context";
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.sessionId").value(id.toString()))
                .andExpect(jsonPath("$.result.status").value("LIVE"));
    }

    @Test
    void playbackRejectsCurrentChannelViewBansBeforeIssuingCredentials() {
        UUID id = createLiveSession().sessionId();
        clearInvocations(issuer);
        doThrow(new AppException(ErrorCode.ACCESS_DENIED)).when(channelAccess)
                .requireViewAccess(any(), any());
        error(ErrorCode.ACCESS_DENIED, () -> playbackService.access(id, UUID.randomUUID()));
        verifyNoInteractions(issuer);
    }

    @Test
    void metadataReadsEveryLifecycleStateWithoutIssuingCredentialsOrChangingHistory() {
        LiveFixture fixture = createLiveSession();
        UUID id = fixture.sessionId();
        clearInvocations(issuer, media);

        assertMetadata(id, StreamSessionStatus.LIVE, StreamVisibility.PUBLIC);
        assertEquals(1, historyCount(id));

        sessionService.metadata(id);
        sessionService.metadata(id);
        assertEquals(1, historyCount(id));
        verifyNoInteractions(issuer, media);

        disconnect(fixture.ingestKeyId(), fixture.sourceId());
        assertMetadata(id, StreamSessionStatus.RECONNECTING, StreamVisibility.PUBLIC);
        assertEquals(2, historyCount(id));

        clock.now = clock.now.plusSeconds(60);
        assertTrue(closureService.expireReconnectIfDue(id));
        assertMetadata(id, StreamSessionStatus.ENDED, StreamVisibility.PUBLIC);
        assertEquals(3, historyCount(id));
        verifyNoInteractions(issuer);
    }

    @Test
    void metadataRejectsMissingSessionsAndVisibilityOutsideTheViewingScope() {
        error(ErrorCode.STREAM_SESSION_NOT_FOUND, () -> sessionService.metadata(UUID.randomUUID()));

        UUID id = createLiveSession().sessionId();
        updateVisibility(id, StreamVisibility.PRIVATE);
        error(ErrorCode.ACCESS_DENIED, () -> sessionService.metadata(id));
    }

    @Test
    void playbackAccessIssuesAnExactReadJwtWithoutIngestAccessAndLeavesSessionUnchanged() throws Exception {
        UUID id = createLiveSession().sessionId();
        UUID viewer = UUID.randomUUID();
        clearInvocations(issuer, media);
        var before = sessions.findById(id).orElseThrow();
        int historyBefore = historyCount(id);

        var access = playbackService.access(id, viewer);

        assertEquals(id, access.sessionId());
        assertEquals("http://localhost:8898/live/" + id + "/index.m3u8", access.playbackUrl());
        assertFalse(access.playbackUrl().contains(access.readToken()));
        assertFalse(access.toString().contains(access.readToken()));
        assertFalse(access.toString().contains("readToken"));

        var jwt = SignedJWT.parse(access.readToken());
        assertTrue(jwt.verify(new RSASSAVerifier((RSAPublicKey) MediaTestSupport.KEYS.getPublic())));
        assertEquals("RS256", jwt.getHeader().getAlgorithm().getName());
        assertEquals("stream-rs256-v1", jwt.getHeader().getKeyID());
        assertEquals("reallive-stream", jwt.getJWTClaimsSet().getIssuer());
        assertEquals(List.of("reallive-media"), jwt.getJWTClaimsSet().getAudience());
        assertEquals(viewer.toString(), jwt.getJWTClaimsSet().getSubject());
        assertEquals(List.of(Map.of("action", "read", "path", "live/" + id)),
                jwt.getJWTClaimsSet().getClaim("mediamtx_permissions"));
        assertEquals(BASE.plus(Duration.ofMinutes(5)), jwt.getJWTClaimsSet().getExpirationTime().toInstant());
        assertEquals(jwt.getJWTClaimsSet().getExpirationTime().toInstant(), access.readExpiresAt());

        var after = sessions.findById(id).orElseThrow();
        assertEquals(before.getStatus(), after.getStatus());
        assertEquals(before.getVisibility(), after.getVisibility());
        assertEquals(before.getEndedAt(), after.getEndedAt());
        assertEquals(historyBefore, historyCount(id));
        verifyNoInteractions(media);
    }

    @Test
    void playbackAccessIsDeniedForEveryNonLiveOrNonPublicState() {
        UUID missing = UUID.randomUUID();
        error(ErrorCode.STREAM_SESSION_NOT_FOUND, () -> playbackService.access(missing, UUID.randomUUID()));

        LiveFixture first = createLiveSession();
        UUID viewer = UUID.randomUUID();
        assertNotNull(playbackService.access(first.sessionId(), viewer));

        LiveFixture privateFixture = createLiveSession();
        updateVisibility(privateFixture.sessionId(), StreamVisibility.PRIVATE);
        error(ErrorCode.ACCESS_DENIED, () -> playbackService.access(privateFixture.sessionId(), viewer));

        disconnect(first.ingestKeyId(), first.sourceId());
        error(ErrorCode.STREAM_SESSION_STATE_INVALID, () -> playbackService.access(first.sessionId(), viewer));

        clock.now = clock.now.plusSeconds(60);
        assertTrue(closureService.expireReconnectIfDue(first.sessionId()));
        error(ErrorCode.STREAM_SESSION_STATE_INVALID, () -> playbackService.access(first.sessionId(), viewer));
    }

    @Test
    void liveMetadataFollowedByCommittedEndMakesNextPlaybackRequestFail() {
        LiveFixture fixture = createLiveSession();
        UUID id = fixture.sessionId();
        assertEquals(StreamSessionStatus.LIVE, sessionService.metadata(id).status());

        disconnect(fixture.ingestKeyId(), fixture.sourceId());
        clock.now = clock.now.plusSeconds(60);
        assertTrue(closureService.expireReconnectIfDue(id));

        assertEquals(StreamSessionStatus.ENDED, sessionService.metadata(id).status());
        error(ErrorCode.STREAM_SESSION_STATE_INVALID, () -> playbackService.access(id, UUID.randomUUID()));
    }

    private LiveFixture createLiveSession() {
        UUID ownerUserId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(eligibility.requireChannelContext(ownerUserId))
                .thenReturn(new StreamPublishContext(ownerUserId, streamerId, channelId, null));
        when(eligibility.requirePublishContext(ownerUserId, categoryId))
                .thenReturn(new StreamPublishContext(ownerUserId, streamerId, channelId, categoryId));

        setupService.saveSetup(ownerUserId, new SaveStreamSetupRequest("Playback stream", categoryId));
        operationWorker.processDueOperations();

        UUID ingestKeyId = configs.findById(channelId).orElseThrow().getCurrentIngestKeyId();
        UUID sourceId = UUID.randomUUID();
        activeIngestSources.put(ingestKeyId,
                new MediaPathStatus("ingest/" + ingestKeyId, true, sourceId, MediaSourceType.RTMP_CONN));

        lifecycleService.onOnline(new MediaIngestEventCommand(
                ingestKeyId, sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(),
                clock.now.atZone(ZoneOffset.UTC).toLocalDateTime()));
        operationWorker.processDueOperations();

        UUID sessionId = sessions.findSlotOccupyingByChannelId(channelId).orElseThrow().getId();
        return new LiveFixture(ownerUserId, channelId, ingestKeyId, sourceId, sessionId);
    }

    private void disconnect(UUID ingestKeyId, UUID sourceId) {
        activeIngestSources.remove(ingestKeyId);
        lifecycleService.onOffline(new MediaIngestEventCommand(
                ingestKeyId, sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(),
                clock.now.atZone(ZoneOffset.UTC).toLocalDateTime()));
        operationWorker.processDueOperations();
    }

    private void updateVisibility(UUID id, StreamVisibility visibility) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            StreamSessionEntity session = sessions.findByIdForUpdate(id).orElseThrow();
            session.setVisibility(visibility);
            sessions.saveAndFlush(session);
        });
    }

    private void assertMetadata(UUID id, StreamSessionStatus status, StreamVisibility visibility) {
        var metadata = sessionService.metadata(id);
        assertEquals(id, metadata.id());
        assertEquals(status, metadata.status());
        assertEquals(visibility, metadata.visibility());
        assertEquals(clock.now, metadata.serverTime());
        assertNotNull(metadata.title());
    }

    private int historyCount(UUID id) {
        return history.findByStreamIdOrderByCreatedAtAscIdAsc(id).size();
    }

    private void error(ErrorCode code, Executable action) {
        assertEquals(code, assertThrows(AppException.class, action).getErrorCode());
    }

    private record LiveFixture(
            UUID ownerUserId,
            UUID channelId,
            UUID ingestKeyId,
            UUID sourceId,
            UUID sessionId
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
