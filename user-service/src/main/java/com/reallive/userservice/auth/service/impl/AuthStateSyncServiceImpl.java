package com.reallive.userservice.auth.service.impl;

import com.reallive.userservice.auth.entity.AuthStateSyncTask;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.repository.AuthStateSyncTaskRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.auth.service.AuthStateSyncService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthStateSyncServiceImpl implements AuthStateSyncService {

    UserRepository userRepository;
    AuthStateSyncTaskRepository taskRepository;
    AuthStateSyncProcessor processor;

    @Override
    @Transactional
    public void changed(UserEntity user) {
        user.setAuthRevision(user.getAuthRevision() + 1);
        userRepository.save(user);
        taskRepository.save(AuthStateSyncTask.builder().userId(user.getId()).revision(user.getAuthRevision())
                .attempts(0).nextAttemptAt(LocalDateTime.now(ZoneOffset.UTC)).build());
        UUID userId = user.getId();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    processor.synchronize(userId);
                }
            });
        } else {
            processor.synchronize(userId);
        }
    }

    @Override
    @Transactional
    public void rolePermissionsChanged(UUID roleId) {
        int page = 0;
        Page<UUID> users;
        do {
            users = userRepository.findIdsByRoleId(roleId, PageRequest.of(page++, 100, Sort.by("id")));
            for (UUID userId : users) {
                userRepository.findByIdForUpdate(userId).ifPresent(this::changed);
            }
        } while (users.hasNext());
    }
}
