package com.reallive.userservice.auth.service.impl;

import com.reallive.userservice.auth.adapter.redis.AuthStateSnapshot;
import com.reallive.userservice.auth.adapter.redis.AuthStateStore;
import com.reallive.userservice.auth.entity.AuthStateSyncTask;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.repository.AuthStateSyncTaskRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.auth.util.UserAuthoritiesMapper;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthStateSyncProcessor {

    UserRepository userRepository;
    AuthStateSyncTaskRepository taskRepository;
    AuthStateStore authStateStore;
    UserAuthoritiesMapper authoritiesMapper;
    ObjectMapper objectMapper;

    @NonFinal
    @Value("${jwt.ttl_refreshToken}")
    long refreshTokenTtl;

    @NonFinal
    @Value("${auth.state.ttl-grace:60s}")
    Duration ttlGrace;

    @NonFinal
    @Value("${auth.state.retry-delay:5s}")
    Duration retryDelay;

    @PostConstruct
    void validateConfiguration() {
        Assert.isTrue(refreshTokenTtl > 0, "Refresh token TTL must be positive");
        Assert.isTrue(ttlGrace != null && !ttlGrace.isNegative(), "Auth-state TTL grace must be nonnegative");
        Assert.isTrue(retryDelay != null && !retryDelay.isNegative() && !retryDelay.isZero(),
                "Auth-state retry delay must be positive");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = AppException.class)
    public void synchronize(UUID userId) {
        AuthStateSyncTask task = taskRepository.findForUpdate(userId).orElse(null);
        if (task == null) {
            return;
        }
        UserEntity user = userRepository.findWithAuthoritiesById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        try {
            publish(user);
            taskRepository.delete(task);
        } catch (AppException exception) {
            task.setAttempts(task.getAttempts() + 1);
            task.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC).plus(retryDelay));
            taskRepository.save(task);
            throw exception;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void backfill(UUID userId) {
        userRepository.findWithAuthoritiesById(userId).ifPresent(this::publish);
    }

    private void publish(UserEntity user) {
        authStateStore.publish(new AuthStateSnapshot(user.getId(), user.getAuthRevision(),
                objectMapper.writeValueAsString(authoritiesMapper.map(user)), user.getStatus().name(),
                Duration.ofSeconds(refreshTokenTtl).plus(ttlGrace)), List.of(), false);
    }
}
