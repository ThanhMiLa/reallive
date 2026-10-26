package com.reallive.streamservice.stream.repository;

import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StreamSessionRepository extends JpaRepository<StreamSessionEntity, UUID> {

    Optional<StreamSessionEntity> findByOwnerUserIdAndCreationRequestId(UUID ownerUserId, UUID creationRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StreamSessionEntity s where s.ownerUserId = :ownerUserId and s.creationRequestId = :requestId")
    Optional<StreamSessionEntity> findCreationForUpdate(@Param("ownerUserId") UUID ownerUserId, @Param("requestId") UUID requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StreamSessionEntity s where s.id = :id")
    Optional<StreamSessionEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("select s from StreamSessionEntity s where s.channelId = :channelId and s.status in ('READY', 'LIVE', 'RECONNECTING')")
    Optional<StreamSessionEntity> findSlotOccupyingByChannelId(@Param("channelId") UUID channelId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StreamSessionEntity s where s.channelId = :channelId and s.status in ('READY', 'LIVE', 'RECONNECTING')")
    Optional<StreamSessionEntity> findSlotOccupyingByChannelIdForUpdate(@Param("channelId") UUID channelId);

    @Query("select s from StreamSessionEntity s where s.ownerUserId = :ownerUserId and s.status in ('READY', 'LIVE', 'RECONNECTING') order by s.createdAt desc")
    List<StreamSessionEntity> findCurrentByOwnerUserId(@Param("ownerUserId") UUID ownerUserId);

    @Query("select s.id from StreamSessionEntity s where s.status = 'RECONNECTING' and s.reconnectDeadlineAt <= :now order by s.reconnectDeadlineAt asc, s.id asc")
    List<UUID> findExpiredReconnectingIds(@Param("now") LocalDateTime now, Pageable pageable);

    Page<StreamSessionEntity> findByVisibilityAndStatusOrderByStartedAtDescIdAsc(
            StreamVisibility visibility,
            StreamSessionStatus status,
            Pageable pageable
    );
}
