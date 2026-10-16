package com.reallive.userservice.auth.adapter.redis;

import com.reallive.userservice.auth.repository.AuthStateSyncTaskRepository;
import com.reallive.userservice.auth.service.impl.AuthStateSyncProcessor;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
@ConditionalOnProperty(name = "auth.state.retry-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class AuthStateSyncWorker {

    AuthStateSyncTaskRepository taskRepository;
    AuthStateSyncProcessor processor;

    @Scheduled(fixedDelayString = "${auth.state.retry-delay:5s}")
    public void retryPending() {
        for (var task : taskRepository.findTop100ByNextAttemptAtLessThanEqualOrderByNextAttemptAt(
                LocalDateTime.now(ZoneOffset.UTC))) {
            try {
                processor.synchronize(task.getUserId());
            } catch (RuntimeException exception) {
                log.warn("Auth state synchronization pending for user {}", task.getUserId());
            }
        }
    }
}
