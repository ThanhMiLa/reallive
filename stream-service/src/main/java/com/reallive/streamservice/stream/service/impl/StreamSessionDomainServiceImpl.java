package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.dto.CreateSessionCommand;
import com.reallive.streamservice.stream.dto.MediaOfflineCommand;
import com.reallive.streamservice.stream.dto.MediaOnlineCommand;
import com.reallive.streamservice.stream.dto.SessionCreationResult;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.StreamStatusHistoryEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.repository.StreamStatusHistoryRepository;
import com.reallive.streamservice.stream.service.StreamSessionDomainService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamSessionDomainServiceImpl implements StreamSessionDomainService {

    StreamSessionRepository sessionRepository;
    StreamStatusHistoryRepository historyRepository;
    PlatformTransactionManager transactionManager;
    Clock streamClock;

    @NonFinal
    @Value("${stream.lifecycle.reconnect-grace:60s}")
    Duration reconnectGraceDuration = Duration.ofSeconds(60);

    @Override
    public SessionCreationResult createOrGetSession(CreateSessionCommand command) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            return transaction.execute(status -> createSessionInTransaction(command));
        } catch (DataIntegrityViolationException exception) {
            if (TransactionSynchronizationManager.isActualTransactionActive() || !isCreationUniqueConflict(exception)) {
                throw exception;
            }
            return transaction.execute(status -> resolveCreationConflict(command));
        } catch (AppException exception) {
            if (exception.getErrorCode() != ErrorCode.STREAM_SESSION_ALREADY_ACTIVE
                    || TransactionSynchronizationManager.isActualTransactionActive()) {
                throw exception;
            }
            return transaction.execute(status -> resolveCreationConflict(command));
        }
    }

    private SessionCreationResult createSessionInTransaction(CreateSessionCommand command) {
        var existingByRequest = sessionRepository.findByOwnerUserIdAndCreationRequestId(
                command.ownerUserId(), command.creationRequestId());
        if (existingByRequest.isPresent()) {
            return existingCreation(existingByRequest.get(), command);
        }

        var occupyingSession = sessionRepository.findSlotOccupyingByChannelIdForUpdate(command.channelId());
        if (occupyingSession.isPresent()) {
            throw new AppException(ErrorCode.STREAM_SESSION_ALREADY_ACTIVE);
        }

        UUID sessionId = UUID.randomUUID();
        String playbackPath = "/live/" + sessionId + "/index.m3u8";
        LocalDateTime now = LocalDateTime.now(streamClock);

        StreamSessionEntity session = StreamSessionEntity.builder()
                .id(sessionId)
                .channelId(command.channelId())
                .streamerId(command.streamerId())
                .ownerUserId(command.ownerUserId())
                .categoryId(command.categoryId())
                .title(command.title().trim())
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.READY)
                .playbackPath(playbackPath)
                .creationRequestId(command.creationRequestId())
                .mediaPathState(MediaPathState.OPEN)
                .build();

        session = sessionRepository.saveAndFlush(session);

        recordHistory(sessionId, null, StreamSessionStatus.READY, "Session created", null, null, now);

        return new SessionCreationResult(session, true);
    }

    private SessionCreationResult resolveCreationConflict(CreateSessionCommand command) {
        return sessionRepository.findByOwnerUserIdAndCreationRequestId(command.ownerUserId(), command.creationRequestId())
                .map(existing -> existingCreation(existing, command))
                .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_ALREADY_ACTIVE));
    }

    private SessionCreationResult existingCreation(StreamSessionEntity existing, CreateSessionCommand command) {
        if (!existing.getChannelId().equals(command.channelId())) {
            throw new AppException(ErrorCode.STREAM_CREATION_REQUEST_CONFLICT);
        }
        return new SessionCreationResult(existing, false);
    }

    private boolean isCreationUniqueConflict(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraint) {
                return "uq_stream_sessions_channel_slot".equals(constraint.getConstraintName())
                        || "uk_stream_sessions_owner_creation_request".equals(constraint.getConstraintName());
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    @Transactional
    public StreamSessionEntity onMediaOnline(MediaOnlineCommand command) {
        StreamSessionEntity session = sessionRepository.findByIdForUpdate(command.streamId())
                .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND));

        if (command.mediaEventId() != null && historyRepository.existsByMediaEventId(command.mediaEventId())) {
            return session;
        }

        if (session.getStatus() == StreamSessionStatus.ENDED) {
            return session;
        }

        LocalDateTime now = LocalDateTime.now(streamClock);

        if (session.getStatus() == StreamSessionStatus.LIVE) {
            if (command.mediaSourceId() != null && command.mediaSourceId().equals(session.getMediaSourceId())) {
                return session;
            }
            session.setMediaSourceId(command.mediaSourceId());
            session.setMediaSourceType(command.mediaSourceType());
            recordHistory(session.getId(), StreamSessionStatus.LIVE, StreamSessionStatus.LIVE,
                    "Publisher reconnected", command.mediaSourceId(), command.mediaEventId(), now);
            return sessionRepository.saveAndFlush(session);
        }

        if (session.getStatus() == StreamSessionStatus.RECONNECTING) {
            LocalDateTime effectiveTime = command.occurredAt() != null ? command.occurredAt() : now;
            if (session.getReconnectDeadlineAt() == null
                    || !effectiveTime.isBefore(session.getReconnectDeadlineAt())
                    || (session.getDisconnectedAt() != null && effectiveTime.isBefore(session.getDisconnectedAt()))) {
                return session;
            }

            session.setStatus(StreamSessionStatus.LIVE);
            session.setMediaSourceId(command.mediaSourceId());
            session.setMediaSourceType(command.mediaSourceType());
            session.setDisconnectedAt(null);
            session.setReconnectDeadlineAt(null);
            recordHistory(session.getId(), StreamSessionStatus.RECONNECTING, StreamSessionStatus.LIVE,
                    "Reconnected within grace period", command.mediaSourceId(), command.mediaEventId(), now);
            return sessionRepository.saveAndFlush(session);
        }

        if (session.getStatus() == StreamSessionStatus.READY) {
            session.setStatus(StreamSessionStatus.LIVE);
            if (session.getStartedAt() == null) {
                session.setStartedAt(now);
            }
            session.setMediaSourceId(command.mediaSourceId());
            session.setMediaSourceType(command.mediaSourceType());
            session.setDisconnectedAt(null);
            session.setReconnectDeadlineAt(null);
            recordHistory(session.getId(), StreamSessionStatus.READY, StreamSessionStatus.LIVE,
                    "Stream started", command.mediaSourceId(), command.mediaEventId(), now);
            return sessionRepository.saveAndFlush(session);
        }

        return session;
    }

    @Override
    @Transactional
    public StreamSessionEntity onMediaOffline(MediaOfflineCommand command) {
        StreamSessionEntity session = sessionRepository.findByIdForUpdate(command.streamId())
                .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND));

        if (command.mediaEventId() != null && historyRepository.existsByMediaEventId(command.mediaEventId())) {
            return session;
        }

        if (session.getStatus() == StreamSessionStatus.ENDED || session.getStatus() == StreamSessionStatus.READY) {
            return session;
        }

        if (session.getStatus() == StreamSessionStatus.RECONNECTING) {
            return session;
        }

        if (session.getStatus() == StreamSessionStatus.LIVE) {
            if (command.mediaSourceId() != null && session.getMediaSourceId() != null
                    && !command.mediaSourceId().equals(session.getMediaSourceId())) {
                return session;
            }

            LocalDateTime now = LocalDateTime.now(streamClock);
            session.setStatus(StreamSessionStatus.RECONNECTING);
            session.setDisconnectedAt(now);
            session.setReconnectDeadlineAt(now.plus(reconnectGraceDuration));
            recordHistory(session.getId(), StreamSessionStatus.LIVE, StreamSessionStatus.RECONNECTING,
                    "Media source offline, reconnect grace started", command.mediaSourceId(), command.mediaEventId(), now);
            return sessionRepository.saveAndFlush(session);
        }

        return session;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StreamSessionEntity> expireReconnectIfDue(UUID streamId) {
        StreamSessionEntity session = sessionRepository.findByIdForUpdate(streamId).orElse(null);
        if (session == null || session.getStatus() != StreamSessionStatus.RECONNECTING) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now(streamClock);
        if (session.getReconnectDeadlineAt() != null && !now.isBefore(session.getReconnectDeadlineAt())) {
            session.setStatus(StreamSessionStatus.ENDED);
            session.setEndedAt(now);
            session.setReconnectDeadlineAt(null);
            session.setMediaPathState(MediaPathState.CLOSED);
            recordHistory(session.getId(), StreamSessionStatus.RECONNECTING, StreamSessionStatus.ENDED,
                    "Reconnect grace period expired", session.getMediaSourceId(), null, now);
            return Optional.of(sessionRepository.saveAndFlush(session));
        }

        return Optional.empty();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public StreamSessionEntity endSession(UUID streamId, UUID ownerUserId, String reason) {
        StreamSessionEntity session = sessionRepository.findByIdForUpdate(streamId)
                .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND));

        if (!session.getOwnerUserId().equals(ownerUserId)) {
            throw new AppException(ErrorCode.STREAM_SESSION_NOT_OWNED);
        }

        if (session.getStatus() == StreamSessionStatus.ENDED) {
            return session;
        }

        StreamSessionStatus previousStatus = session.getStatus();
        LocalDateTime now = LocalDateTime.now(streamClock);

        session.setStatus(StreamSessionStatus.ENDED);
        session.setEndedAt(now);
        session.setReconnectDeadlineAt(null);
        session.setMediaPathState(MediaPathState.CLOSED);
        recordHistory(session.getId(), previousStatus, StreamSessionStatus.ENDED,
                reason != null ? reason : "Session ended by owner", session.getMediaSourceId(), null, now);

        return sessionRepository.saveAndFlush(session);
    }

    private void recordHistory(UUID streamId, StreamSessionStatus previousStatus, StreamSessionStatus status,
                               String reason, UUID mediaSourceId, UUID mediaEventId, LocalDateTime now) {
        StreamStatusHistoryEntity history = StreamStatusHistoryEntity.builder()
                .streamId(streamId)
                .previousStatus(previousStatus)
                .status(status)
                .reason(reason)
                .mediaSourceId(mediaSourceId)
                .mediaEventId(mediaEventId)
                .createdAt(now)
                .build();
        historyRepository.save(history);
    }
}
