package com.reallive.streamservice.stream.service;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.MediaTestSupport;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.adapter.media.MediaJwtIssuer;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.dto.CreateSessionCommand;
import com.reallive.streamservice.stream.dto.MediaOfflineCommand;
import com.reallive.streamservice.stream.dto.MediaOnlineCommand;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class StreamPlaybackPostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final Instant BASE = Instant.parse("2026-10-29T03:00:00Z");

    @Autowired private StreamSessionService sessionService;
    @Autowired private StreamPlaybackService playbackService;
    @Autowired private StreamSessionClosureService closureService;
    @Autowired private StreamSessionDomainService domain;
    @Autowired private StreamSessionRepository sessions;
    @Autowired private StreamStatusHistoryRepository history;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MutableClock clock;
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
        clock.now = BASE;
        when(media.getPath(any())).thenReturn(Optional.empty());
        when(media.getSource(any())).thenReturn(Optional.empty());
    }

    @Test
    void metadataReadsEveryLifecycleStateWithoutIssuingCredentialsOrChangingHistory() {
        UUID id = createSession();
        assertMetadata(id, StreamSessionStatus.READY, StreamVisibility.PUBLIC);
        assertEquals(1, historyCount(id));

        sessionService.metadata(id);
        sessionService.metadata(id);
        assertEquals(1, historyCount(id));
        verifyNoInteractions(issuer, media);

        goLive(id);
        assertMetadata(id, StreamSessionStatus.LIVE, StreamVisibility.PUBLIC);
        assertEquals(2, historyCount(id));

        disconnect(id, currentSource(id));
        assertMetadata(id, StreamSessionStatus.RECONNECTING, StreamVisibility.PUBLIC);
        assertEquals(3, historyCount(id));

        assertEquals(StreamSessionStatus.ENDED, closureService.end(id, ownerOf(id), null).status());
        assertMetadata(id, StreamSessionStatus.ENDED, StreamVisibility.PUBLIC);
        assertEquals(4, historyCount(id));
        verifyNoInteractions(issuer);
    }

    @Test
    void metadataRejectsMissingSessionsAndVisibilityOutsideTheViewingScope() {
        error(ErrorCode.STREAM_SESSION_NOT_FOUND, () -> sessionService.metadata(UUID.randomUUID()));

        UUID id = createSession();
        updateVisibility(id, StreamVisibility.PRIVATE);
        error(ErrorCode.ACCESS_DENIED, () -> sessionService.metadata(id));
    }

    @Test
    void playbackAccessIssuesAnExactReadJwtAndLeavesTheSessionUnchanged() throws Exception {
        UUID id = createSession();
        goLive(id);
        UUID viewer = UUID.randomUUID();
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

        UUID ready = createSession();
        error(ErrorCode.STREAM_SESSION_STATE_INVALID, () -> playbackService.access(ready, UUID.randomUUID()));

        goLive(ready);
        UUID viewer = UUID.randomUUID();
        assertNotNull(playbackService.access(ready, viewer));

        UUID privateSession = createSession();
        goLive(privateSession);
        updateVisibility(privateSession, StreamVisibility.PRIVATE);
        error(ErrorCode.ACCESS_DENIED, () -> playbackService.access(privateSession, viewer));

        disconnect(ready, currentSource(ready));
        error(ErrorCode.STREAM_SESSION_STATE_INVALID, () -> playbackService.access(ready, viewer));
        closureService.end(ready, ownerOf(ready), null);
        error(ErrorCode.STREAM_SESSION_STATE_INVALID, () -> playbackService.access(ready, viewer));

        UUID ended = createSession();
        goLive(ended);
        closureService.end(ended, ownerOf(ended), null);
        error(ErrorCode.STREAM_SESSION_STATE_INVALID, () -> playbackService.access(ended, viewer));
    }

    @Test
    void liveMetadataFollowedByCommittedEndMakesTheNextPlaybackRequestFail() {
        UUID id = createSession();
        goLive(id);
        assertEquals(StreamSessionStatus.LIVE, sessionService.metadata(id).status());

        closureService.end(id, ownerOf(id), null);

        assertEquals(StreamSessionStatus.ENDED, sessionService.metadata(id).status());
        error(ErrorCode.STREAM_SESSION_STATE_INVALID, () -> playbackService.access(id, UUID.randomUUID()));
    }

    private UUID createSession() {
        var command = new CreateSessionCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "Playback stream", UUID.randomUUID());
        return domain.createOrGetSession(command).session().getId();
    }

    private void goLive(UUID id) {
        domain.onMediaOnline(new MediaOnlineCommand(id, UUID.randomUUID(), MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
    }

    private void disconnect(UUID id, UUID sourceId) {
        domain.onMediaOffline(new MediaOfflineCommand(id, sourceId, MediaSourceType.RTMP_CONN, UUID.randomUUID(), null));
    }

    private UUID currentSource(UUID id) {
        return sessions.findById(id).orElseThrow().getMediaSourceId();
    }

    private UUID ownerOf(UUID id) {
        return sessions.findById(id).orElseThrow().getOwnerUserId();
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
        assertEquals(BASE, metadata.serverTime());
        assertNotNull(metadata.title());
    }

    private int historyCount(UUID id) {
        return history.findByStreamIdOrderByCreatedAtAscIdAsc(id).size();
    }

    private void error(ErrorCode code, Executable action) {
        assertEquals(code, assertThrows(AppException.class, action).getErrorCode());
    }

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
