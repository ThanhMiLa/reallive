package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.media.MediaJwtIssuer;
import com.reallive.streamservice.stream.adapter.media.dto.MediaPathStatus;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.dto.MediaEventOutcome;
import com.reallive.streamservice.stream.dto.MediaIngestEventCommand;
import com.reallive.streamservice.stream.dto.response.MediaEventResponse;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.ChannelStreamConfigStatus;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.mapper.MediaEventMapper;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.repository.StreamStatusHistoryRepository;
import com.reallive.streamservice.stream.service.StreamEligibilityService;
import com.reallive.streamservice.stream.service.StreamMediaLifecycleService;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamMediaLifecycleServiceImpl implements StreamMediaLifecycleService {
    ChannelStreamConfigRepository configs;
    StreamSessionRepository sessions;
    StreamStatusHistoryRepository history;
    MediaServerAdapter media;
    MediaJwtIssuer issuer;
    StreamEligibilityService eligibility;
    StreamSessionLifecycleSupport lifecycle;
    MediaEventMapper mapper;
    PlatformTransactionManager transactionManager;

    @Override
    public MediaEventResponse onOnline(MediaIngestEventCommand command) {
        validate(command);
        var snapshot = configs.findByCurrentIngestKeyId(command.ingestKeyId()).orElse(null);
        if (snapshot == null || snapshot.getStatus() != ChannelStreamConfigStatus.ACTIVE)
            return result(null, command, false);
        try {
            eligibility.requirePublishContext(snapshot.getOwnerUserId(), snapshot.getCategoryId());
        } catch (AppException exception) {
            if (exception.getErrorCode() != ErrorCode.STREAMER_NOT_ELIGIBLE
                    && exception.getErrorCode() != ErrorCode.STREAM_CHANNEL_NOT_ELIGIBLE
                    && exception.getErrorCode() != ErrorCode.STREAM_CATEGORY_INVALID
                    && exception.getErrorCode() != ErrorCode.ACCESS_DENIED) throw exception;
            return rejectPublisher(command, exception.getMessage());
        }
        return new TransactionTemplate(transactionManager)
                .execute(status -> onlineInTransaction(command));
    }

    private MediaEventResponse onlineInTransaction(MediaIngestEventCommand command) {
        var config = configs.findByIngestKeyIdForUpdate(command.ingestKeyId()).orElse(null);
        if (config == null || config.getStatus() != ChannelStreamConfigStatus.ACTIVE)
            return result(null, command, false);
        var existing =
                sessions.findSlotOccupyingByChannelIdForUpdate(config.getChannelId()).orElse(null);
        if (history.existsByMediaEventId(command.mediaEventId()))
            return result(existing, command, false);
        var source = media.getIngestSource(command.ingestKeyId()).orElse(null);
        if (!matches(source, command)) return result(existing, command, false);
        LocalDateTime onlineAt =
                lifecycle.boundOccurredAt(
                        source.onlineTime() == null
                                ? command.occurredAt()
                                : lifecycle.toUtcLocalDateTime(source.onlineTime()),
                        lifecycle.nowUtc(),
                        null);
        if (existing != null
                && existing.getStatus() == StreamSessionStatus.LIVE
                && command.mediaSourceId().equals(existing.getMediaSourceId()))
            return result(existing, command, false);
        if (existing != null
                && (existing.getStatus() == StreamSessionStatus.LIVE
                        || lifecycle.isReconnectExpired(existing, onlineAt))) {
            lifecycle.closePlaybackPathAndVerify(existing.getId());
            lifecycle.finalizeEndedSession(
                    config, existing, lifecycle.nowUtc(), "Previous publisher ended", null, null);
            existing = null;
        }
        boolean creating = existing == null;
        UUID sessionId = creating ? UUID.randomUUID() : existing.getId();
        media.ensurePlaybackPath(
                sessionId,
                command.ingestKeyId(),
                issuer.issueRelayRead(command.ingestKeyId()).value());
        awaitPlayback(sessionId);
        if (!matches(media.getIngestSource(command.ingestKeyId()).orElse(null), command)) {
            media.deletePlaybackPath(sessionId);
            return result(existing, command, false);
        }
        var session =
                creating
                        ? StreamSessionEntity.builder()
                                .id(sessionId)
                                .channelId(config.getChannelId())
                                .streamerId(config.getStreamerId())
                                .ownerUserId(config.getOwnerUserId())
                                .title(config.getTitle())
                                .categoryId(config.getCategoryId())
                                .visibility(StreamVisibility.PUBLIC)
                                .ingestKeyId(command.ingestKeyId())
                                .playbackPath("/live/" + sessionId + "/index.m3u8")
                                .startedAt(onlineAt)
                                .build()
                        : existing;
        StreamSessionStatus previous = session.getStatus();
        session.setStatus(StreamSessionStatus.LIVE);
        session.setMediaPathState(MediaPathState.OPEN);
        session.setMediaSourceId(command.mediaSourceId());
        session.setMediaSourceType(command.mediaSourceType());
        session.setDisconnectedAt(null);
        session.setReconnectDeadlineAt(null);
        sessions.saveAndFlush(session);
        lifecycle.recordHistory(
                sessionId,
                previous,
                StreamSessionStatus.LIVE,
                creating ? "OBS started broadcasting" : "OBS reconnected",
                command.mediaSourceId(),
                command.mediaEventId());
        return result(session, command, true);
    }

    @Override
    public MediaEventResponse onOffline(MediaIngestEventCommand command) {
        validate(command);
        return new TransactionTemplate(transactionManager)
                .execute(
                        status -> {
                            var config =
                                    configs.findByIngestKeyIdForUpdate(command.ingestKeyId())
                                            .orElse(null);
                            if (config == null) return result(null, command, false);
                            var session =
                                    sessions.findSlotOccupyingByChannelIdForUpdate(
                                                    config.getChannelId())
                                            .orElse(null);
                            if (session == null
                                    || session.getStatus() != StreamSessionStatus.LIVE
                                    || !command.mediaSourceId().equals(session.getMediaSourceId())
                                    || command.mediaSourceType() != session.getMediaSourceType()
                                    || history.existsByMediaEventId(command.mediaEventId()))
                                return result(session, command, false);
                            if (matches(
                                    media.getIngestSource(command.ingestKeyId()).orElse(null),
                                    command)) return result(session, command, false);
                            lifecycle.closePlaybackPathAndVerify(session.getId());
                            LocalDateTime offlineAt =
                                    lifecycle.boundOccurredAt(
                                            command.occurredAt(),
                                            lifecycle.nowUtc(),
                                            session.getStartedAt());
                            session.setStatus(StreamSessionStatus.RECONNECTING);
                            session.setMediaPathState(MediaPathState.CLOSED);
                            session.setDisconnectedAt(offlineAt);
                            session.setReconnectDeadlineAt(
                                    offlineAt.plus(lifecycle.reconnectGrace()));
                            session.setMediaSourceId(null);
                            session.setMediaSourceType(null);
                            sessions.saveAndFlush(session);
                            lifecycle.recordHistory(
                                    session.getId(),
                                    StreamSessionStatus.LIVE,
                                    StreamSessionStatus.RECONNECTING,
                                    "OBS disconnected",
                                    command.mediaSourceId(),
                                    command.mediaEventId());
                            return result(session, command, true);
                        });
    }

    private MediaEventResponse rejectPublisher(MediaIngestEventCommand command, String reason) {
        return new TransactionTemplate(transactionManager)
                .execute(
                        status -> {
                            var config =
                                    configs.findByIngestKeyIdForUpdate(command.ingestKeyId())
                                            .orElse(null);
                            if (config == null
                                    || !matches(
                                            media.getIngestSource(command.ingestKeyId())
                                                    .orElse(null),
                                            command)) {
                                return result(null, command, false);
                            }
                            var session =
                                    sessions.findSlotOccupyingByChannelIdForUpdate(
                                                    config.getChannelId())
                                            .orElse(null);
                            if (session != null) {
                                lifecycle.closePlaybackPathAndVerify(session.getId());
                                lifecycle.finalizeEndedSession(
                                        config,
                                        session,
                                        lifecycle.nowUtc(),
                                        "Publisher eligibility rejected",
                                        null,
                                        null);
                            }
                            lifecycle.disconnectPublisherAndDeleteIngestAndVerify(
                                    command.ingestKeyId(),
                                    command.mediaSourceId(),
                                    command.mediaSourceType(),
                                    true);
                            config.setCurrentIngestKeyId(null);
                            config.setStatus(ChannelStreamConfigStatus.BLOCKED);
                            config.setBlockedReason(lifecycle.truncate(reason, 500));
                            configs.saveAndFlush(config);
                            return result(session, command, false);
                        });
    }

    private void awaitPlayback(UUID sessionId) {
        for (int attempt = 0; attempt < 15; attempt++) {
            if (media.getPlaybackSource(sessionId).filter(MediaPathStatus::online).isPresent())
                return;
            try {
                Thread.sleep(150);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
            }
        }
        throw new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
    }

    private boolean matches(MediaPathStatus source, MediaIngestEventCommand command) {
        return source != null
                && source.online()
                && command.mediaSourceId().equals(source.sourceId())
                && command.mediaSourceType() == source.sourceType();
    }

    private void validate(MediaIngestEventCommand command) {
        if (command == null
                || command.ingestKeyId() == null
                || command.mediaEventId() == null
                || command.mediaSourceId() == null
                || command.mediaSourceType() == null
                || !command.mediaSourceType().isPublisher()) {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }
    }

    private MediaEventResponse result(
            StreamSessionEntity session, MediaIngestEventCommand command, boolean applied) {
        return mapper.toResponse(
                session == null ? null : session.getId(),
                command.mediaEventId(),
                applied ? MediaEventOutcome.APPLIED : MediaEventOutcome.IGNORED,
                session == null ? null : session.getStatus());
    }
}
