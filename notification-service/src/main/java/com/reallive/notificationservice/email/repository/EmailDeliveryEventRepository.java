package com.reallive.notificationservice.email.repository;

import com.reallive.notificationservice.email.entity.EmailDeliveryEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface EmailDeliveryEventRepository extends JpaRepository<EmailDeliveryEventEntity, UUID> {

    Optional<EmailDeliveryEventEntity> findBySgEventId(String sgEventId);

    boolean existsBySgEventId(String sgEventId);

    @Query("""
            SELECT event.sgEventId
            FROM EmailDeliveryEventEntity event
            WHERE event.sgEventId IN :sgEventIds
            """)
    Set<String> findExistingSgEventIds(@Param("sgEventIds") Collection<String> sgEventIds);

    Page<EmailDeliveryEventEntity> findByNotificationIdOrderByProviderTimestampAscReceivedAtAsc(
            UUID notificationId, Pageable pageable);
}
