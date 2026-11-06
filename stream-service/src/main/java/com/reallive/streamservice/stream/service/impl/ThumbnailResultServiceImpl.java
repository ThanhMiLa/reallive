package com.reallive.streamservice.stream.service.impl;

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
import com.reallive.streamservice.stream.service.ThumbnailResultService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ThumbnailResultServiceImpl implements ThumbnailResultService {

    StreamSessionRepository sessions;
    ThumbnailProperties properties;
    Clock streamClock;

    @Override
    @Transactional
    public ThumbnailResultResponse apply(ThumbnailResultRequest request) {
        String publicUrl = validateAndBuildPublicUrl(request);
        StreamSessionEntity session = sessions.findByIdForUpdate(request.sessionId()).orElse(null);
        LocalDateTime now = LocalDateTime.ofInstant(streamClock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        if (!eligible(session, request, now)) {
            return new ThumbnailResultResponse(ThumbnailResultOutcome.IGNORED);
        }

        session.setThumbnailUrl(publicUrl);
        session.setThumbnailObjectKey(request.objectKey());
        session.setThumbnailUpdatedAt(now);
        session.setThumbnailJobStatus(ThumbnailJobStatus.SUCCEEDED);
        return new ThumbnailResultResponse(ThumbnailResultOutcome.APPLIED);
    }

    private String validateAndBuildPublicUrl(ThumbnailResultRequest request) {
        if (request == null
                || request.jobId() == null
                || request.sessionId() == null
                || !"image/jpeg".equals(request.contentType())
                || request.sizeBytes() < 3
                || request.sizeBytes() > properties.getMaxJpegBytes()
                || request.width() < 1
                || request.height() < 1) {
            throw invalidResult();
        }
        String expectedKey = expectedObjectKey(request.sessionId(), request.jobId());
        if (expectedKey.length() > 500 || !expectedKey.equals(request.objectKey())) {
            throw invalidResult();
        }
        String publicUrl = publicUrl(request.objectKey());
        if (publicUrl.length() > 1_000) {
            throw invalidResult();
        }
        return publicUrl;
    }

    private boolean eligible(StreamSessionEntity session, ThumbnailResultRequest request, LocalDateTime now) {
        if (session == null
                || !request.jobId().equals(session.getThumbnailJobId())
                || !isWaiting(session.getThumbnailJobStatus())
                || session.getStatus() != StreamSessionStatus.LIVE) {
            return false;
        }
        LocalDateTime deadline = session.getThumbnailJobDeadlineAt();
        return deadline != null && deadline.isAfter(now);
    }

    private boolean isWaiting(ThumbnailJobStatus status) {
        return status == ThumbnailJobStatus.PENDING_DISPATCH || status == ThumbnailJobStatus.ACCEPTED;
    }

    private String expectedObjectKey(UUID sessionId, UUID jobId) {
        return "livestream-thumbnails/" + sessionId + "/" + jobId + ".jpg";
    }

    private String publicUrl(String objectKey) {
        String base = properties.getPublicBaseUrl();
        if (base == null || base.isBlank()) {
            throw invalidResult();
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + objectKey;
    }

    private AppException invalidResult() {
        return new AppException(ErrorCode.THUMBNAIL_RESULT_INVALID);
    }
}
