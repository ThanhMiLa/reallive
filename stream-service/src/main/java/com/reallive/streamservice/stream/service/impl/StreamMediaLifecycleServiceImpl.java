    package com.reallive.streamservice.stream.service.impl;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.adapter.media.MediaServerAdapter;
import com.reallive.streamservice.stream.dto.MediaEventOutcome;
import com.reallive.streamservice.stream.dto.MediaOfflineCommand;
import com.reallive.streamservice.stream.dto.MediaOnlineCommand;
import com.reallive.streamservice.stream.dto.SessionEventResult;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.StreamMediaLifecycleService;
import com.reallive.streamservice.stream.service.StreamSessionDomainService;
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
public class StreamMediaLifecycleServiceImpl implements StreamMediaLifecycleService {

    StreamSessionRepository sessions;
    StreamSessionDomainService domain;
    MediaServerAdapter media;
    PlatformTransactionManager transactionManager;

    @Override
    public SessionEventResult onOnline(MediaOnlineCommand command) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var session = sessions.findByIdForUpdate(command.streamId())
                    .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND));

            if (session.getStatus() == StreamSessionStatus.ENDED) {
                return new SessionEventResult(session, MediaEventOutcome.IGNORED);
            }

            var sourceObservation = media.getSource(command.streamId());
            if (sourceObservation.isEmpty()
                    || !sourceObservation.get().online()
                    || !command.mediaSourceId().equals(sourceObservation.get().sourceId())
                    || command.mediaSourceType() != sourceObservation.get().sourceType()) {
                return new SessionEventResult(session, MediaEventOutcome.IGNORED);
            }

            StreamSessionStatus initialStatus = session.getStatus();
            UUID initialSourceId = session.getMediaSourceId();

            var updated = domain.onMediaOnline(command);

            boolean changed = updated.getStatus() != initialStatus
                    || (command.mediaSourceId() != null && !command.mediaSourceId().equals(initialSourceId));

            return new SessionEventResult(updated, changed ? MediaEventOutcome.APPLIED : MediaEventOutcome.IGNORED);
        });
    }

    @Override
    public SessionEventResult onOffline(MediaOfflineCommand command) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var session = sessions.findByIdForUpdate(command.streamId())
                    .orElseThrow(() -> new AppException(ErrorCode.STREAM_SESSION_NOT_FOUND));

            if (session.getStatus() == StreamSessionStatus.ENDED || session.getStatus() == StreamSessionStatus.READY) {
                return new SessionEventResult(session, MediaEventOutcome.IGNORED);
            }

            var sourceObservation = media.getSource(command.streamId());
            if (sourceObservation.isPresent() && sourceObservation.get().online()) {
                return new SessionEventResult(session, MediaEventOutcome.IGNORED);
            }

            StreamSessionStatus initialStatus = session.getStatus();

            var updated = domain.onMediaOffline(command);

            boolean changed = updated.getStatus() != initialStatus;

            return new SessionEventResult(updated, changed ? MediaEventOutcome.APPLIED : MediaEventOutcome.IGNORED);
        });
    }
}
