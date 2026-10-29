package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.media.MediaJwtIssuer;
import com.reallive.streamservice.stream.adapter.media.MediaPathConfiguration;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.client.channel.ChannelClient;
import com.reallive.streamservice.stream.dto.CreateSessionCommand;
import com.reallive.streamservice.stream.dto.SessionProvisioningResult;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import com.reallive.streamservice.stream.dto.request.CreateStreamSessionRequest;
import com.reallive.streamservice.stream.dto.request.UpdateStreamSessionRequest;
import com.reallive.streamservice.stream.dto.response.CreateStreamSessionResponse;
import com.reallive.streamservice.stream.dto.response.StreamMetadataResponse;
import com.reallive.streamservice.stream.dto.response.StreamPublishCredentialsResponse;
import com.reallive.streamservice.stream.dto.response.StreamSessionResponse;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.mapper.StreamSessionMapper;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.StreamEligibilityService;
import com.reallive.streamservice.stream.service.StreamSessionDomainService;
import com.reallive.streamservice.stream.service.StreamSessionService;
import com.reallive.streamservice.stream.util.StreamCreationConstraints;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Transactional(propagation = Propagation.NEVER)
public class StreamSessionServiceImpl implements StreamSessionService {

    StreamSessionDomainService domain;
    StreamEligibilityService eligibility;
    ChannelClient channelClient;
    StreamSessionRepository sessions;
    MediaServerAdapter media;
    MediaJwtIssuer issuer;
    StreamSessionMapper mapper;
    PlatformTransactionManager transactionManager;
    Clock streamClock;

    @Override
    public SessionProvisioningResult create(UUID ownerUserId, CreateStreamSessionRequest request) {
        var context = eligibility.requirePublishContext(ownerUserId, request.categoryId());
        var command = new CreateSessionCommand(context.channelId(), context.streamerId(), ownerUserId,
                request.categoryId(), request.title(), request.requestId());
        var transaction = new TransactionTemplate(transactionManager);
        try {
            return transaction.execute(status -> {
                var creation = domain.createOrGetSession(command);
                var session = requireOwnedLocked(creation.session().getId(), ownerUserId);
                requirePublishable(session);
                if (creation.created()) {
                    media.ensurePath(session.getId());
                } else {
                    requireMediaPath(session.getId());
                }
                return creationResponse(session, creation.created());
            });
        } catch (DataIntegrityViolationException exception) {
            if (!StreamCreationConstraints.isCreationConflict(exception)) {
                throw exception;
            }
        } catch (AppException exception) {
            if (exception.getErrorCode() != ErrorCode.STREAM_SESSION_ALREADY_ACTIVE) {
                throw exception;
            }
        }
        // A uniqueness loser must read the winner only after the enclosing transaction rolled back.
        return transaction.execute(status -> {
            var existing = sessions.findCreationForUpdate(ownerUserId, request.requestId())
                    .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_ALREADY_ACTIVE));
            if (!existing.getChannelId().equals(context.channelId())) {
                throw new AppException(ErrorCode.STREAM_CREATION_REQUEST_CONFLICT);
            }
            var session = requireOwnedLocked(existing.getId(), ownerUserId);
            requirePublishable(session);
            requireMediaPath(session.getId());
            return creationResponse(session, false);
        });
    }

    @Override
    public StreamSessionResponse update(UUID sessionId, UUID ownerUserId, UpdateStreamSessionRequest request) {
        requireOwned(sessionId, ownerUserId);
        if (request.isCategoryProvided()) {
            channelClient.requireCategory(request.getCategoryId());
        }
        return new TransactionTemplate(transactionManager).execute(status -> {
            var session = requireOwnedLocked(sessionId, ownerUserId);
            requireActive(session);
            if (request.isTitleProvided()) {
                session.setTitle(request.getTitle());
            }
            if (request.isCategoryProvided()) {
                session.setCategoryId(request.getCategoryId());
            }
            sessions.saveAndFlush(session);
            return mapper.toOwnerResponse(session, streamClock.instant());
        });
    }

    @Override
    public StreamPublishCredentialsResponse publishCredentials(UUID sessionId, UUID ownerUserId) {
        var existing = requireOwned(sessionId, ownerUserId);
        requirePublishable(existing);
        var context = eligibility.requirePublishContext(ownerUserId, existing.getCategoryId());
        return new TransactionTemplate(transactionManager).execute(status -> {
            var session = requireOwnedLocked(sessionId, ownerUserId);
            requireContext(session, context);
            requirePublishable(session);
            requireMediaPath(sessionId);
            return issuer.publishCredentials(sessionId, ownerUserId);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public StreamMetadataResponse metadata(UUID sessionId) {
        var session = sessions.findById(sessionId)
                .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND));
        requireViewableVisibility(session);
        return mapper.toMetadataResponse(session, streamClock.instant());
    }

    @Override
    public StreamSessionResponse current(UUID ownerUserId) {
        var current = sessions.findCurrentByOwnerUserId(ownerUserId);
        if (current.isEmpty()) {
            return null;
        }
        var session = current.getFirst();
        boolean ready = publishable(session) && media.getPath(session.getId())
                .filter(MediaPathConfiguration::acceptsPublisher).isPresent();
        return mapper.toOwnerResponse(session, streamClock.instant(), ready);
    }

    private SessionProvisioningResult creationResponse(StreamSessionEntity session, boolean created) {
        var response = new CreateStreamSessionResponse(mapper.toOwnerResponse(session, streamClock.instant()),
                issuer.publishCredentials(session.getId(), session.getOwnerUserId()));
        return new SessionProvisioningResult(response, created);
    }

    private StreamSessionEntity requireOwned(UUID sessionId, UUID ownerUserId) {
        var session = sessions.findById(sessionId)
                .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND));
        checkOwner(session, ownerUserId);
        return session;
    }

    private StreamSessionEntity requireOwnedLocked(UUID sessionId, UUID ownerUserId) {
        var session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND));
        checkOwner(session, ownerUserId);
        return session;
    }

    private void checkOwner(StreamSessionEntity session, UUID ownerUserId) {
        if (!session.getOwnerUserId().equals(ownerUserId)) {
            throw new AppException(ErrorCode.STREAM_SESSION_NOT_OWNED);
        }
    }

    private void requireContext(StreamSessionEntity session, StreamPublishContext context) {
        if (!session.getChannelId().equals(context.channelId()) || !session.getStreamerId().equals(context.streamerId())) {
            throw new AppException(ErrorCode.STREAM_CHANNEL_NOT_ELIGIBLE);
        }
    }

    private void requireActive(StreamSessionEntity session) {
        if (session.getStatus() == StreamSessionStatus.ENDED) {
            throw new AppException(ErrorCode.STREAM_SESSION_STATE_INVALID);
        }
    }

    private void requireViewableVisibility(StreamSessionEntity session) {
        if (session.getVisibility() != StreamVisibility.PUBLIC) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
    }

    private void requirePublishable(StreamSessionEntity session) {
        if (!publishable(session)) {
            throw new AppException(ErrorCode.STREAM_SESSION_STATE_INVALID);
        }
    }

    private boolean publishable(StreamSessionEntity session) {
        return session.getStatus() != StreamSessionStatus.ENDED && session.getMediaPathState() == MediaPathState.OPEN
                && (session.getStatus() != StreamSessionStatus.RECONNECTING
                || (session.getReconnectDeadlineAt() != null
                && LocalDateTime.ofInstant(streamClock.instant(), ZoneOffset.UTC).isBefore(session.getReconnectDeadlineAt())));
    }

    private void requireMediaPath(UUID sessionId) {
        if (media.getPath(sessionId).filter(MediaPathConfiguration::acceptsPublisher).isEmpty()) {
            throw new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
        }
    }
}
