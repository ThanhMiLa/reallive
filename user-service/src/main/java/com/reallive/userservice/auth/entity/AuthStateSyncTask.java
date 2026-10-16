package com.reallive.userservice.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "auth_state_sync_tasks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AuthStateSyncTask {

    @Id
    @Column(name = "user_id")
    UUID userId;

    @Column(nullable = false)
    long revision;

    @Column(nullable = false)
    int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    LocalDateTime nextAttemptAt;
}
