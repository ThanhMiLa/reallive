package com.reallive.streamservice.stream.scheduler;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.service.StreamSessionClosureService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@ConditionalOnProperty(name = "stream.lifecycle.reconnect-sweep-enabled", matchIfMissing = true)
public class ReconnectExpirationScheduler {

    StreamSessionRepository streamSessionRepository;
    StreamSessionClosureService closure;
    Clock streamClock;

    @NonFinal
    @Value("${stream.lifecycle.reconnect-sweep-batch-size:20}")
    int batchSize = 20;

    @Scheduled(fixedDelayString = "${stream.lifecycle.reconnect-sweep-delay:1s}")
    public void sweepExpiredReconnections() {
        LocalDateTime now = LocalDateTime.now(streamClock);
        List<UUID> expiredIds = streamSessionRepository.findExpiredReconnectingIds(now, PageRequest.of(0, batchSize));
        for (UUID sessionId : expiredIds) {
            try {
                closure.expireReconnectIfDue(sessionId);
            } catch (AppException e) {
                log.warn("Failed to expire reconnecting session {}: {}", sessionId, e.getMessage());
                if (e.getErrorCode() == ErrorCode.MEDIA_SERVER_UNAVAILABLE) {
                    break;
                }
            } catch (Exception e) {
                log.error("Unexpected error while expiring reconnecting session {}", sessionId, e);
            }
        }
    }
}
