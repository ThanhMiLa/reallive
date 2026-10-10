package com.reallive.notificationservice.email.repository;

import com.reallive.notificationservice.email.entity.EmailNotificationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailNotificationRepository extends JpaRepository<EmailNotificationEntity, UUID> {

    Optional<EmailNotificationEntity> findByEventId(UUID eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select notification from EmailNotificationEntity notification where notification.eventId = :eventId")
    Optional<EmailNotificationEntity> findByEventIdForUpdate(@Param("eventId") UUID eventId);

    boolean existsByEventId(UUID eventId);

    Page<EmailNotificationEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
