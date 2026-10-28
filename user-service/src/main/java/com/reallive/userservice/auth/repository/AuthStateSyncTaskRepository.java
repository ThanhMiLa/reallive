package com.reallive.userservice.auth.repository;

import com.reallive.userservice.auth.entity.AuthStateSyncTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthStateSyncTaskRepository extends JpaRepository<AuthStateSyncTask, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT task
            FROM AuthStateSyncTask task
            WHERE task.userId = :userId
            """)
    Optional<AuthStateSyncTask> findForUpdate(UUID userId);

    List<AuthStateSyncTask> findTop100ByNextAttemptAtLessThanEqualOrderByNextAttemptAt(LocalDateTime dueAt);
}
