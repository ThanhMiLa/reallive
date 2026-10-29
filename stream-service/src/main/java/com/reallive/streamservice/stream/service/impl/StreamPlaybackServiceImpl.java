package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.common.configuration.MediaProperties;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.media.MediaJwtIssuer;
import com.reallive.streamservice.stream.dto.response.StreamPlaybackAccessResponse;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.StreamPlaybackService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Transactional(propagation = Propagation.NEVER)
public class StreamPlaybackServiceImpl implements StreamPlaybackService {

    StreamSessionRepository sessions;
    MediaJwtIssuer issuer;
    MediaProperties properties;
    PlatformTransactionManager transactionManager;

    @Override
    public StreamPlaybackAccessResponse access(UUID sessionId, UUID viewerUserId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var session = sessions.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND));
            requireWatchable(session);
            var token = issuer.issueRead(session.getId(), viewerUserId);
            return new StreamPlaybackAccessResponse(session.getId(), playbackUrl(session), token.value(), token.expiresAt());
        });
    }

    private void requireWatchable(StreamSessionEntity session) {
        if (session.getVisibility() != StreamVisibility.PUBLIC) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        if (session.getStatus() != StreamSessionStatus.LIVE) {
            throw new AppException(ErrorCode.STREAM_SESSION_STATE_INVALID);
        }
    }

    private String playbackUrl(StreamSessionEntity session) {
        String baseUrl = properties.getHlsPublicBaseUrl();
        String path = session.getPlaybackPath().startsWith("/")
                ? session.getPlaybackPath() : "/" + session.getPlaybackPath();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + path : baseUrl + path;
    }
}
