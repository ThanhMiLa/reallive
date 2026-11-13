package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.common.configuration.MediaProperties;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.crypto.CredentialCipher;
import com.reallive.streamservice.stream.adapter.media.MediaJwtIssuer;
import com.reallive.streamservice.stream.adapter.media.MediaPathStatus;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import com.reallive.streamservice.stream.dto.request.SaveStreamSetupRequest;
import com.reallive.streamservice.stream.entity.ChannelStreamConfigEntity;
import com.reallive.streamservice.stream.entity.ChannelStreamCredentialEntity;
import com.reallive.streamservice.stream.entity.StreamMediaOperationEntity;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.StreamStatusHistoryEntity;
import com.reallive.streamservice.stream.entity.enums.ChannelStreamConfigStatus;
import com.reallive.streamservice.stream.entity.enums.CredentialStatus;
import com.reallive.streamservice.stream.entity.enums.MediaOperationStatus;
import com.reallive.streamservice.stream.entity.enums.MediaOperationType;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
import com.reallive.streamservice.stream.repository.ChannelStreamCredentialRepository;
import com.reallive.streamservice.stream.repository.StreamMediaOperationRepository;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.repository.StreamStatusHistoryRepository;
import com.reallive.streamservice.stream.service.StreamEligibilityService;
import com.reallive.streamservice.stream.service.StreamMediaOperationWorker;
import com.reallive.streamservice.stream.service.StreamViewerPresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamStateMachineServiceTest {

    private static final Instant BASE_INSTANT = Instant.parse("2026-11-05T10:00:00Z");

    @Mock
    private StreamEligibilityService eligibilityService;

    @Mock
    private ChannelStreamConfigRepository configRepository;

    @Mock
    private ChannelStreamCredentialRepository credentialRepository;

    @Mock
    private StreamMediaOperationRepository operationRepository;

    @Mock
    private StreamSessionRepository sessionRepository;

    @Mock
    private StreamStatusHistoryRepository historyRepository;

    @Mock
    private MediaJwtIssuer jwtIssuer;

    @Mock
    private CredentialCipher cipher;

    @Mock
    private MediaServerAdapter mediaAdapter;

    @Mock
    private StreamViewerPresenceService viewerPresence;

    @Mock
    private StreamMediaOperationWorker wakeupWorker;

    @Captor
    private ArgumentCaptor<StreamStatusHistoryEntity> historyCaptor;

    private MutableClock clock;
    private PlatformTransactionManager transactionManager;
    private StreamSessionLifecycleSupport support;
    private StreamSetupServiceImpl setupService;
    private StreamMediaOperationWorkerImpl operationWorker;
    private StreamSessionClosureServiceImpl closureService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BASE_INSTANT, ZoneOffset.UTC);
        transactionManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };

        support = new StreamSessionLifecycleSupport(
                sessionRepository,
                historyRepository,
                operationRepository,
                mediaAdapter,
                viewerPresence,
                new ObjectMapper(),
                clock
        );

        MediaProperties properties = new MediaProperties();
        properties.setRtmpPublicUrl("rtmp://localhost:1935/ingest");

        setupService = new StreamSetupServiceImpl(
                eligibilityService,
                configRepository,
                credentialRepository,
                operationRepository,
                sessionRepository,
                jwtIssuer,
                cipher,
                mediaAdapter,
                properties,
                wakeupWorker,
                support,
                transactionManager
        );

        operationWorker = new StreamMediaOperationWorkerImpl(
                operationRepository,
                configRepository,
                credentialRepository,
                sessionRepository,
                mediaAdapter,
                cipher,
                eligibilityService,
                support,
                transactionManager,
                null,
                null
        );

        closureService = new StreamSessionClosureServiceImpl(
                configRepository,
                sessionRepository,
                operationRepository,
                support,
                transactionManager
        );

        lenient().when(configRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(credentialRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(operationRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(sessionRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void retryDelayScheduleFollowsPlanBackoffSeconds() {
        assertEquals(Duration.ofSeconds(1), operationWorker.retryDelayForAttempt(1));
        assertEquals(Duration.ofSeconds(2), operationWorker.retryDelayForAttempt(2));
        assertEquals(Duration.ofSeconds(5), operationWorker.retryDelayForAttempt(3));
        assertEquals(Duration.ofSeconds(10), operationWorker.retryDelayForAttempt(4));
        assertEquals(Duration.ofSeconds(30), operationWorker.retryDelayForAttempt(5));
        assertEquals(Duration.ofSeconds(30), operationWorker.retryDelayForAttempt(9));
    }

    @Test
    void sanitizeErrorRedactsJwtTokensFromRelayOrIngestUrls() {
        RuntimeException failure = new RuntimeException(
                "Control API call failed for rtmp://127.0.0.1:1935/ingest/abc?token=secret.jwt.value during patch");
        String sanitized = support.sanitizeError(failure);
        assertFalse(sanitized.contains("secret.jwt.value"));
        assertTrue(sanitized.contains("token=REDACTED"));
    }

    @Test
    void saveSetupValidatesBlankTitleAndNullCategoryBeforeCallingEligibilityOrMedia() {
        UUID ownerId = UUID.randomUUID();
        assertEquals(ErrorCode.STREAM_REQUEST_INVALID,
                assertThrows(AppException.class, () -> setupService.saveSetup(
                        ownerId, new SaveStreamSetupRequest("   ", UUID.randomUUID()))).getErrorCode());
        assertEquals(ErrorCode.STREAM_REQUEST_INVALID,
                assertThrows(AppException.class, () -> setupService.saveSetup(
                        ownerId, new SaveStreamSetupRequest("Valid Title", null))).getErrorCode());
        verify(eligibilityService, never()).requirePublishContext(any(), any());
        verify(mediaAdapter, never()).ensureIngestPath(any());
    }

    @Test
    void processOnlineFailsStaleGenerationAndNeverTouchesMedia() {
        UUID channelId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        UUID ingestKeyId = UUID.randomUUID();

        ChannelStreamConfigEntity config = ChannelStreamConfigEntity.builder()
                .channelId(channelId)
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .title("Test")
                .categoryId(UUID.randomUUID())
                .visibility(StreamVisibility.PUBLIC)
                .status(ChannelStreamConfigStatus.ACTIVE)
                .currentIngestKeyId(UUID.randomUUID())
                .currentCredentialGeneration(2L)
                .mediaRevision(3L)
                .build();

        StreamMediaOperationEntity op = StreamMediaOperationEntity.builder()
                .id(opId)
                .channelId(channelId)
                .credentialGeneration(1L)
                .mediaRevision(1L)
                .operationType(MediaOperationType.PROCESS_ONLINE)
                .status(MediaOperationStatus.PENDING)
                .targetIngestKeyId(ingestKeyId)
                .targetSourceId(UUID.randomUUID())
                .targetSourceType(MediaSourceType.RTMP_CONN)
                .nextAttemptAt(LocalDateTime.now(clock).minusSeconds(1))
                .build();

        when(operationRepository.findById(opId)).thenReturn(Optional.of(op));
        when(operationRepository.findChannelIdById(opId)).thenReturn(Optional.of(channelId));
        when(operationRepository.findByIdForUpdate(opId)).thenReturn(Optional.of(op));
        when(configRepository.findByIdForUpdate(channelId)).thenReturn(Optional.of(config));

        assertTrue(operationWorker.executeSingleOperation(opId));
        assertEquals(MediaOperationStatus.CANCELLED, op.getStatus());
        verify(mediaAdapter, never()).ensurePlaybackPath(any(), any(), any());
    }

    @Test
    void expireReconnectExpiresAtExact60SecondBoundaryOnlyWhenMediaClosed() {
        UUID channelId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        LocalDateTime disconnect = LocalDateTime.ofInstant(BASE_INSTANT, ZoneOffset.UTC);

        ChannelStreamConfigEntity config = ChannelStreamConfigEntity.builder()
                .channelId(channelId)
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .title("Test")
                .categoryId(UUID.randomUUID())
                .visibility(StreamVisibility.PUBLIC)
                .status(ChannelStreamConfigStatus.ACTIVE)
                .currentIngestKeyId(UUID.randomUUID())
                .currentCredentialGeneration(1L)
                .mediaRevision(1L)
                .build();

        StreamSessionEntity session = StreamSessionEntity.builder()
                .id(sessionId)
                .channelId(channelId)
                .ownerUserId(config.getOwnerUserId())
                .status(StreamSessionStatus.RECONNECTING)
                .mediaPathState(MediaPathState.OPEN)
                .startedAt(disconnect.minusMinutes(10))
                .disconnectedAt(disconnect)
                .reconnectDeadlineAt(disconnect.plusSeconds(60))
                .build();

        when(sessionRepository.findChannelIdById(sessionId)).thenReturn(Optional.of(channelId));
        when(configRepository.findByIdForUpdate(channelId)).thenReturn(Optional.of(config));
        when(sessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(operationRepository.findByChannelIdAndStatusIn(channelId, List.of(MediaOperationStatus.PENDING, MediaOperationStatus.RUNNING)))
                .thenReturn(List.of());

        clock.advance(Duration.ofSeconds(59));
        assertFalse(closureService.expireReconnectIfDue(sessionId));
        verify(mediaAdapter, never()).deletePlaybackPath(any());

        clock.advance(Duration.ofSeconds(1));
        when(mediaAdapter.getPlaybackPath(sessionId)).thenReturn(Optional.empty());
        when(mediaAdapter.getPlaybackSource(sessionId)).thenReturn(Optional.empty());
        assertTrue(closureService.expireReconnectIfDue(sessionId));

        assertEquals(StreamSessionStatus.ENDED, session.getStatus());
        assertEquals(MediaPathState.CLOSED, session.getMediaPathState());
        assertNotNull(session.getEndedAt());
        assertNull(session.getReconnectDeadlineAt());
        verify(mediaAdapter).deletePlaybackPath(sessionId);
        verify(viewerPresence).closeAfterCommit(sessionId);
        verify(historyRepository).save(historyCaptor.capture());
        assertEquals(StreamSessionStatus.ENDED, historyCaptor.getValue().getStatus());
    }

    @Test
    void expireReconnectSkipsWhenPreDeadlineOnlineOperationIsPending() {
        UUID channelId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        LocalDateTime disconnect = LocalDateTime.ofInstant(BASE_INSTANT, ZoneOffset.UTC);

        ChannelStreamConfigEntity config = ChannelStreamConfigEntity.builder()
                .channelId(channelId)
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .title("Test")
                .categoryId(UUID.randomUUID())
                .visibility(StreamVisibility.PUBLIC)
                .status(ChannelStreamConfigStatus.ACTIVE)
                .build();

        StreamSessionEntity session = StreamSessionEntity.builder()
                .id(sessionId)
                .channelId(channelId)
                .status(StreamSessionStatus.RECONNECTING)
                .mediaPathState(MediaPathState.OPEN)
                .startedAt(disconnect.minusMinutes(5))
                .disconnectedAt(disconnect)
                .reconnectDeadlineAt(disconnect.plusSeconds(60))
                .build();

        StreamMediaOperationEntity preDeadlineOnline = StreamMediaOperationEntity.builder()
                .channelId(channelId)
                .operationType(MediaOperationType.PROCESS_ONLINE)
                .status(MediaOperationStatus.PENDING)
                .payload(support.encodePayload(new StreamSessionLifecycleSupport.OperationPayload(
                        disconnect.plusSeconds(45), null, null, null, null, UUID.randomUUID(), null)))
                .build();

        when(sessionRepository.findChannelIdById(sessionId)).thenReturn(Optional.of(channelId));
        when(configRepository.findByIdForUpdate(channelId)).thenReturn(Optional.of(config));
        when(sessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(operationRepository.findByChannelIdAndStatusIn(channelId, List.of(MediaOperationStatus.PENDING, MediaOperationStatus.RUNNING)))
                .thenReturn(List.of(preDeadlineOnline));

        clock.advance(Duration.ofSeconds(65));
        assertFalse(closureService.expireReconnectIfDue(sessionId));
        verify(mediaAdapter, never()).deletePlaybackPath(any());
        assertEquals(StreamSessionStatus.RECONNECTING, session.getStatus());
    }

    private static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
