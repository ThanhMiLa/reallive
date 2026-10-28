package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.dto.response.StreamSessionResponse;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.mapper.StreamSessionMapper;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.StreamSessionClosureService;
import com.reallive.streamservice.stream.service.StreamSessionDomainService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Transactional(propagation = Propagation.NEVER)
public class StreamSessionClosureServiceImpl implements StreamSessionClosureService {

    StreamSessionRepository streamSessionRepository;
    StreamSessionDomainService domain;
    MediaServerAdapter media;
    StreamSessionMapper mapper;
    PlatformTransactionManager transactionManager;
    Clock streamClock;

    @Override
    public StreamSessionResponse end(UUID sessionId, UUID ownerUserId, String reason) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var session = streamSessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND));
            if (!session.getOwnerUserId().equals(ownerUserId)) {
                throw new AppException(ErrorCode.STREAM_SESSION_NOT_OWNED);
            }
            if (session.getStatus() == StreamSessionStatus.ENDED) {
                return mapper.toOwnerResponse(session, streamClock.instant());
            }
            closeMedia(sessionId);
            return mapper.toOwnerResponse(domain.endSession(sessionId, ownerUserId, reason), streamClock.instant());
        });
    }

    @Override
    public boolean expireReconnectIfDue(UUID sessionId) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> {
            var sessionOpt = streamSessionRepository.findByIdForUpdate(sessionId);
            if (sessionOpt.isEmpty()) {
                return false;
            }
            var session = sessionOpt.get();
            if (session.getStatus() != StreamSessionStatus.RECONNECTING) {
                return false;
            }
            LocalDateTime now = LocalDateTime.now(streamClock);
            if (session.getReconnectDeadlineAt() == null || now.isBefore(session.getReconnectDeadlineAt())) {
                return false;
            }
            closeMedia(sessionId);
            return domain.expireReconnectIfDue(sessionId).isPresent();
        }));
    }

    private void closeMedia(UUID sessionId) {
        media.deletePath(sessionId);
        if (media.getPath(sessionId).isPresent()) {
            throw new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
        }
        var source = media.getSource(sessionId);
        if (source.isPresent() && source.get().online()) {
            media.kick(source.get().sourceType(), source.get().sourceId());
            if (media.getSource(sessionId).filter(observed -> observed.online()).isPresent()) {
                throw new AppException(ErrorCode.MEDIA_SERVER_UNAVAILABLE);
            }
        }
    }
}
