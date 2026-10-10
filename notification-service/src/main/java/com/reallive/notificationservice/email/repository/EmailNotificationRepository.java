package com.reallive.notificationservice.email.repository;

import com.reallive.notificationservice.email.entity.EmailNotificationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailNotificationRepository extends JpaRepository<EmailNotificationEntity, UUID> {

    Optional<EmailNotificationEntity> findByEventId(UUID eventId);

    boolean existsByEventId(UUID eventId);

    Page<EmailNotificationEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
