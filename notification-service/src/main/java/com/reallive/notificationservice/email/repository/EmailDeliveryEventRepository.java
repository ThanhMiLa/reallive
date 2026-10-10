package com.reallive.notificationservice.email.repository;

import com.reallive.notificationservice.email.entity.EmailDeliveryEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailDeliveryEventRepository extends JpaRepository<EmailDeliveryEventEntity, UUID> {

    Optional<EmailDeliveryEventEntity> findBySgEventId(String sgEventId);

    boolean existsBySgEventId(String sgEventId);

    Page<EmailDeliveryEventEntity> findByNotificationIdOrderByProviderTimestampAscReceivedAtAsc(
            UUID notificationId, Pageable pageable);
}
