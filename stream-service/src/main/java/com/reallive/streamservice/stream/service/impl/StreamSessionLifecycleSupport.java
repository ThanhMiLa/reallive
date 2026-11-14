package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.media.dto.MediaPathConfiguration;
import com.reallive.streamservice.stream.adapter.media.dto.MediaPathStatus;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.entity.ChannelStreamConfigEntity;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.StreamStatusHistoryEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.repository.StreamStatusHistoryRepository;
import com.reallive.streamservice.stream.service.StreamViewerPresenceService;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
class StreamSessionLifecycleSupport {

    StreamSessionRepository sessions;
    StreamStatusHistoryRepository history;
    MediaServerAdapter mediaAdapter;
    StreamViewerPresenceService viewerPresence;
    Clock streamClock;

    @NonFinal
    @Value("${stream.lifecycle.reconnect-grace:60s}")
    Duration reconnectGrace = Duration.ofSeconds(60);

    LocalDateTime nowUtc() {
        return LocalDateTime.now(streamClock).truncatedTo(ChronoUnit.MICROS);
    }

    Duration reconnectGrace() {
        return reconnectGrace;
    }

    LocalDateTime toUtcLocalDateTime(Instant instant) {
        return instant == null
                ? null
                : LocalDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    LocalDateTime boundOccurredAt(
            LocalDateTime occurredAt, LocalDateTime now, LocalDateTime lowerBound) {
        LocalDateTime effective =
                occurredAt == null || occurredAt.isAfter(now)
                        ? now
                        : occurredAt.truncatedTo(ChronoUnit.MICROS);
        if (lowerBound != null && effective.isBefore(lowerBound)) {
            return lowerBound;
        }
        return effective;
    }

    boolean isReconnectExpired(StreamSessionEntity session, LocalDateTime effectiveNow) {
        return session.getReconnectDeadlineAt() != null
                && !effectiveNow.isBefore(session.getReconnectDeadlineAt());
    }

    void closePlaybackPathAndVerify(UUID sessionId) {
        mediaAdapter.deletePlaybackPath(sessionId);
        Optional<MediaPathConfiguration> config = mediaAdapter.getPlaybackPath(sessionId);
        Optional<MediaPathStatus> status = mediaAdapter.getPlaybackSource(sessionId);
        if (config.isPresent() || status.isPresent()) {
            throw new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
        }
    }

    void disconnectPublisherAndDeleteIngestAndVerify(
            UUID ingestKeyId,
            UUID targetSourceId,
            MediaSourceType targetSourceType,
            boolean kickAnyPublisherOnRevokedPath) {
        Optional<MediaPathStatus> observed = mediaAdapter.getIngestSource(ingestKeyId);
        if (targetSourceId != null && targetSourceType != null && targetSourceType.isPublisher()) {
            mediaAdapter.kick(targetSourceType, targetSourceId);
        }
        if (kickAnyPublisherOnRevokedPath && observed.isPresent()) {
            MediaPathStatus current = observed.get();
            if (current.sourceId() != null
                    && current.sourceType() != null
                    && current.sourceType().isPublisher()
                    && !current.sourceId().equals(targetSourceId)) {
                mediaAdapter.kick(current.sourceType(), current.sourceId());
            }
        }
        mediaAdapter.deleteIngestPath(ingestKeyId);
        Optional<MediaPathConfiguration> remainingConfig = mediaAdapter.getIngestPath(ingestKeyId);
        Optional<MediaPathStatus> remainingStatus = mediaAdapter.getIngestSource(ingestKeyId);
        if (remainingConfig.isPresent() || remainingStatus.isPresent()) {
            throw new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
        }
    }

    boolean finalizeEndedSession(
            ChannelStreamConfigEntity config,
            StreamSessionEntity session,
            LocalDateTime endedAt,
            String reason,
            UUID mediaSourceId,
            UUID mediaEventId) {
        if (session == null || session.getStatus() == StreamSessionStatus.ENDED) {
            return false;
        }
        StreamSessionStatus previousStatus = session.getStatus();
        String safeReason = truncate(reason, 500);
        session.setEndRequested(true);
        if (session.getEndRequestedAt() == null) {
            session.setEndRequestedAt(endedAt);
        }
        if (session.getEndReason() == null && safeReason != null) {
            session.setEndReason(safeReason);
        }
        session.setStatus(StreamSessionStatus.ENDED);
        session.setMediaPathState(MediaPathState.CLOSED);
        session.setEndedAt(endedAt);
        session.setMediaSourceId(null);
        session.setMediaSourceType(null);
        session.setReconnectDeadlineAt(null);
        session.setThumbnailJobId(null);
        session.setThumbnailJobStatus(null);
        session.setThumbnailJobDeadlineAt(null);
        sessions.saveAndFlush(session);

        recordHistory(
                session.getId(),
                previousStatus,
                StreamSessionStatus.ENDED,
                safeReason,
                mediaSourceId,
                mediaEventId);
        viewerPresence.closeAfterCommit(session.getId());
        return true;
    }

    void recordHistory(
            UUID streamId,
            StreamSessionStatus previousStatus,
            StreamSessionStatus status,
            String reason,
            UUID mediaSourceId,
            UUID mediaEventId) {
        history.save(
                StreamStatusHistoryEntity.builder()
                        .streamId(streamId)
                        .previousStatus(previousStatus)
                        .status(status)
                        .reason(truncate(reason, 500))
                        .mediaSourceId(mediaSourceId)
                        .mediaEventId(mediaEventId)
                        .createdAt(nowUtc())
                        .build());
    }

    String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
