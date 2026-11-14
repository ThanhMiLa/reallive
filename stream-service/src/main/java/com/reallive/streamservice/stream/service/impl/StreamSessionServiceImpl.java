package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.media.dto.MediaPathConfiguration;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.dto.response.StreamMetadataResponse;
import com.reallive.streamservice.stream.dto.response.StreamSessionResponse;
import com.reallive.streamservice.stream.entity.ChannelStreamConfigEntity;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.ChannelStreamConfigStatus;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.mapper.StreamSessionMapper;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.StreamSessionService;
import com.reallive.streamservice.stream.service.StreamViewerPresenceService;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Transactional(propagation = Propagation.NEVER)
public class StreamSessionServiceImpl implements StreamSessionService {

    StreamSessionRepository sessions;
    ChannelStreamConfigRepository configs;
    MediaServerAdapter mediaAdapter;
    StreamSessionMapper mapper;
    PlatformTransactionManager transactionManager;
    Clock streamClock;
    StreamViewerPresenceService viewerPresence;

    @Override
    public StreamMetadataResponse metadata(UUID sessionId) {
        TransactionTemplate readTx = new TransactionTemplate(transactionManager);
        readTx.setReadOnly(true);
        StreamSessionEntity session =
                readTx.execute(
                        status -> {
                            StreamSessionEntity found =
                                    sessions.findById(sessionId)
                                            .orElseThrow(
                                                    () ->
                                                            new AppException(
                                                                    ErrorCode
                                                                            .STREAM_SESSION_NOT_FOUND));
                            if (found.getVisibility() != StreamVisibility.PUBLIC) {
                                throw new AppException(ErrorCode.ACCESS_DENIED);
                            }
                            return found;
                        });
        Long viewerCount =
                viewerPresence == null
                        ? null
                        : viewerPresence.count(sessionId, session.getStatus());
        return mapper.toMetadataResponse(session, streamClock.instant(), viewerCount);
    }

    @Override
    public StreamSessionResponse current(UUID ownerUserId) {
        TransactionTemplate readTx = new TransactionTemplate(transactionManager);
        readTx.setReadOnly(true);
        CurrentSnapshot snapshot =
                readTx.execute(
                        status -> {
                            List<StreamSessionEntity> active =
                                    sessions.findCurrentByOwnerUserId(ownerUserId);
                            if (active.isEmpty()) {
                                return null;
                            }
                            StreamSessionEntity session = active.getFirst();
                            Optional<ChannelStreamConfigEntity> configOpt =
                                    configs.findById(session.getChannelId());
                            UUID ingestKeyId =
                                    configOpt
                                            .map(ChannelStreamConfigEntity::getCurrentIngestKeyId)
                                            .orElse(null);
                            boolean configActive =
                                    configOpt
                                            .map(
                                                    config ->
                                                            config.getStatus()
                                                                    == ChannelStreamConfigStatus
                                                                            .ACTIVE)
                                            .orElse(false);
                            return new CurrentSnapshot(session, configActive, ingestKeyId);
                        });

        if (snapshot == null) {
            return null;
        }

        StreamSessionEntity session = snapshot.session();
        Instant now = streamClock.instant();
        boolean publishReady =
                snapshot.configActive()
                        && snapshot.ingestKeyId() != null
                        && !session.isEndRequested()
                        && publishable(session, now)
                        && isIngestPathReadySafely(snapshot.ingestKeyId());
        return mapper.toOwnerResponse(session, now, publishReady);
    }

    private boolean publishable(StreamSessionEntity session, Instant now) {
        if (session.getStatus() == StreamSessionStatus.LIVE) {
            return true;
        }
        if (session.getStatus() == StreamSessionStatus.RECONNECTING) {
            LocalDateTime deadline = session.getReconnectDeadlineAt();
            return deadline != null && now.isBefore(deadline.toInstant(ZoneOffset.UTC));
        }
        return false;
    }

    private boolean isIngestPathReadySafely(UUID ingestKeyId) {
        try {
            return mediaAdapter
                    .getIngestPath(ingestKeyId)
                    .filter(MediaPathConfiguration::acceptsPublisher)
                    .isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private record CurrentSnapshot(
        StreamSessionEntity session,
        boolean configActive,
        UUID ingestKeyId
) {}
}
