package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.configuration.ThumbnailProperties;
import com.reallive.streamservice.stream.dto.request.ThumbnailResultRequest;
import com.reallive.streamservice.stream.dto.response.ThumbnailResultOutcome;
import com.reallive.streamservice.stream.dto.response.ThumbnailResultResponse;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.ThumbnailJobStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.impl.ThumbnailResultServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThumbnailResultServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final String BASE_URL = "https://thumbnails.example.com/base/";

    @Mock
    private StreamSessionRepository sessions;

    private ThumbnailProperties properties;
    private ThumbnailResultServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new ThumbnailProperties();
        properties.setPublicBaseUrl(BASE_URL);
        properties.setMaxJpegBytes(5_242_880);
        service = new ThumbnailResultServiceImpl(
                sessions, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void appliesWaitingJobAndRetainsItsIdentityAndDeadline() {
        UUID jobId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        LocalDateTime deadline = localNow().plusSeconds(120);
        StreamSessionEntity session = session(sessionId, jobId, ThumbnailJobStatus.ACCEPTED, deadline);
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        ThumbnailResultResponse response = service.apply(request(jobId, sessionId));

        assertEquals(ThumbnailResultOutcome.APPLIED, response.outcome());
        assertEquals("https://thumbnails.example.com/base/livestream-thumbnails/"
                + sessionId + "/" + jobId + ".jpg", session.getThumbnailUrl());
        assertEquals(objectKey(sessionId, jobId), session.getThumbnailObjectKey());
        assertEquals(localNow(), session.getThumbnailUpdatedAt());
        assertEquals(ThumbnailJobStatus.SUCCEEDED, session.getThumbnailJobStatus());
        assertEquals(jobId, session.getThumbnailJobId());
        assertEquals(deadline, session.getThumbnailJobDeadlineAt());
    }

    @Test
    void appliesAPendingJobBeforeAcknowledgement() {
        UUID jobId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        StreamSessionEntity session = session(
                sessionId, jobId, ThumbnailJobStatus.PENDING_DISPATCH, localNow().plusSeconds(120));
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        assertEquals(ThumbnailResultOutcome.APPLIED,
                service.apply(request(jobId, sessionId)).outcome());
    }

    @Test
    void ignoresAMissingSessionWithoutWriting() {
        UUID jobId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.empty());

        assertEquals(ThumbnailResultOutcome.IGNORED,
                service.apply(request(jobId, sessionId)).outcome());
    }

    @Test
    void ignoresAReplacedJobIdWithoutChangingTheStoredThumbnail() {
        UUID currentJobId = UUID.randomUUID();
        UUID oldJobId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        StreamSessionEntity session = session(
                sessionId, currentJobId, ThumbnailJobStatus.ACCEPTED, localNow().plusSeconds(120));
        session.setThumbnailUrl("https://thumbnails.example.com/old.jpg");
        session.setThumbnailObjectKey("old-key");
        session.setThumbnailUpdatedAt(localNow().minusSeconds(10));
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        assertEquals(ThumbnailResultOutcome.IGNORED,
                service.apply(request(oldJobId, sessionId)).outcome());
        assertEquals("https://thumbnails.example.com/old.jpg", session.getThumbnailUrl());
        assertEquals("old-key", session.getThumbnailObjectKey());
        assertEquals(localNow().minusSeconds(10), session.getThumbnailUpdatedAt());
        assertEquals(ThumbnailJobStatus.ACCEPTED, session.getThumbnailJobStatus());
    }

    @Test
    void ignoresAJobThatAlreadySucceededOrFailed() {
        for (ThumbnailJobStatus status : new ThumbnailJobStatus[]{
                ThumbnailJobStatus.SUCCEEDED, ThumbnailJobStatus.FAILED}) {
            UUID jobId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            StreamSessionEntity session = session(
                    sessionId, jobId, status, localNow().plusSeconds(120));
            session.setThumbnailUpdatedAt(localNow().minusSeconds(10));
            when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

            assertEquals(ThumbnailResultOutcome.IGNORED,
                    service.apply(request(jobId, sessionId)).outcome());
            assertEquals(localNow().minusSeconds(10), session.getThumbnailUpdatedAt());
        }
    }

    @Test
    void ignoresAJobForASessionThatIsNoLongerLive() {
        UUID jobId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        StreamSessionEntity session = session(
                sessionId, jobId, ThumbnailJobStatus.ACCEPTED, localNow().plusSeconds(120));
        session.setStatus(StreamSessionStatus.ENDED);
        session.setThumbnailUpdatedAt(localNow().minusSeconds(10));
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        assertEquals(ThumbnailResultOutcome.IGNORED,
                service.apply(request(jobId, sessionId)).outcome());
        assertEquals(localNow().minusSeconds(10), session.getThumbnailUpdatedAt());
        assertNull(session.getThumbnailObjectKey());
    }

    @Test
    void ignoresAJobWhoseDeadlineHasExpiredOrIsExactlyNow() {
        for (LocalDateTime deadline : new LocalDateTime[]{localNow(), localNow().minusSeconds(1)}) {
            UUID jobId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            StreamSessionEntity session = session(sessionId, jobId, ThumbnailJobStatus.ACCEPTED, deadline);
            session.setThumbnailUpdatedAt(localNow().minusSeconds(10));
            when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

            assertEquals(ThumbnailResultOutcome.IGNORED,
                    service.apply(request(jobId, sessionId)).outcome());
            assertEquals(localNow().minusSeconds(10), session.getThumbnailUpdatedAt());
        }
    }

    @Test
    void rejectsMismatchedObjectKeysAndInvalidMetadataBeforeLocking() {
        UUID jobId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ThumbnailResultRequest valid = request(jobId, sessionId);

        assertInvalid(new ThumbnailResultRequest(jobId, sessionId,
                objectKey(sessionId, UUID.randomUUID()), "image/jpeg", 48_210, 640, 360));
        assertInvalid(new ThumbnailResultRequest(jobId, sessionId,
                "livestream-thumbnails/" + sessionId + "/" + jobId + ".png",
                "image/jpeg", 48_210, 640, 360));
        assertInvalid(new ThumbnailResultRequest(jobId, sessionId,
                valid.objectKey(), "image/png", 48_210, 640, 360));
        assertInvalid(new ThumbnailResultRequest(jobId, sessionId,
                valid.objectKey(), "image/jpeg", 2, 640, 360));
        assertInvalid(new ThumbnailResultRequest(jobId, sessionId,
                valid.objectKey(), "image/jpeg", 5_242_881, 640, 360));
        assertInvalid(new ThumbnailResultRequest(jobId, sessionId,
                valid.objectKey(), "image/jpeg", 48_210, 0, 360));
        assertInvalid(new ThumbnailResultRequest(jobId, sessionId,
                valid.objectKey(), "image/jpeg", 48_210, 640, -1));

        verify(sessions, never()).findByIdForUpdate(any());
    }

    @Test
    void rejectsAPublicUrlThatWouldOverflowTheStoredColumn() {
        properties.setPublicBaseUrl("https://thumbnails.example.com/" + "a".repeat(1_000));
        UUID jobId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        AppException exception = assertThrows(AppException.class,
                () -> service.apply(request(jobId, sessionId)));

        assertEquals(ErrorCode.THUMBNAIL_RESULT_INVALID, exception.getErrorCode());
        verify(sessions, never()).findByIdForUpdate(any());
    }

    private void assertInvalid(ThumbnailResultRequest request) {
        AppException exception = assertThrows(AppException.class, () -> service.apply(request));
        assertEquals(ErrorCode.THUMBNAIL_RESULT_INVALID, exception.getErrorCode());
    }

    private ThumbnailResultRequest request(UUID jobId, UUID sessionId) {
        return new ThumbnailResultRequest(
                jobId, sessionId, objectKey(sessionId, jobId),
                "image/jpeg", 48_210, 640, 360);
    }

    private String objectKey(UUID sessionId, UUID jobId) {
        return "livestream-thumbnails/" + sessionId + "/" + jobId + ".jpg";
    }

    private StreamSessionEntity session(
            UUID sessionId,
            UUID jobId,
            ThumbnailJobStatus jobStatus,
            LocalDateTime deadline
    ) {
        return StreamSessionEntity.builder()
                .id(sessionId)
                .status(StreamSessionStatus.LIVE)
                .thumbnailJobId(jobId)
                .thumbnailJobStatus(jobStatus)
                .thumbnailJobDeadlineAt(deadline)
                .build();
    }

    private LocalDateTime localNow() {
        return LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }
}
