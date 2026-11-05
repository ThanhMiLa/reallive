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
    @Query("""
            SELECT s
            FROM StreamSessionEntity s
            WHERE s.ownerUserId = :ownerUserId
              AND s.creationRequestId = :requestId
            """)
    Optional<StreamSessionEntity> findCreationForUpdate(@Param("ownerUserId") UUID ownerUserId, @Param("requestId") UUID requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s
            FROM StreamSessionEntity s
            WHERE s.id = :id
            """)
    Optional<StreamSessionEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT s
            FROM StreamSessionEntity s
            WHERE s.channelId = :channelId
              AND s.status IN ('READY', 'LIVE', 'RECONNECTING')
            """)
    Optional<StreamSessionEntity> findSlotOccupyingByChannelId(@Param("channelId") UUID channelId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s
            FROM StreamSessionEntity s
            WHERE s.channelId = :channelId
              AND s.status IN ('READY', 'LIVE', 'RECONNECTING')
            """)
    Optional<StreamSessionEntity> findSlotOccupyingByChannelIdForUpdate(@Param("channelId") UUID channelId);

    @Query("""
            SELECT s
            FROM StreamSessionEntity s
            WHERE s.ownerUserId = :ownerUserId
              AND s.status IN ('READY', 'LIVE', 'RECONNECTING')
            ORDER BY s.createdAt DESC
            """)
    List<StreamSessionEntity> findCurrentByOwnerUserId(@Param("ownerUserId") UUID ownerUserId);

    @Query("""
            SELECT s.id
            FROM StreamSessionEntity s
            WHERE s.status = 'RECONNECTING'
              AND s.reconnectDeadlineAt <= :now
            ORDER BY s.reconnectDeadlineAt ASC, s.id ASC
            """)
    List<UUID> findExpiredReconnectingIds(@Param("now") LocalDateTime now, Pageable pageable);

    @Query("""
            SELECT s.id
            FROM StreamSessionEntity s
            WHERE s.thumbnailJobStatus IN ('PENDING_DISPATCH', 'ACCEPTED')
              AND s.thumbnailJobDeadlineAt <= :now
            ORDER BY s.thumbnailJobDeadlineAt ASC, s.id ASC
            """)
    List<UUID> findExpiredThumbnailJobIds(@Param("now") LocalDateTime now, Pageable pageable);

    @Query("""
            SELECT s.id
            FROM StreamSessionEntity s
            WHERE s.status = 'LIVE'
              AND s.thumbnailJobStatus = 'PENDING_DISPATCH'
              AND s.thumbnailJobDeadlineAt > :now
            ORDER BY s.thumbnailJobDeadlineAt ASC, s.id ASC
            """)
    List<UUID> findPendingThumbnailDispatchIds(@Param("now") LocalDateTime now, Pageable pageable);

    @Query(value = """
            SELECT id
            FROM stream_sessions
            WHERE status = 'LIVE'
              AND (thumbnail_job_status IS NULL OR thumbnail_job_status NOT IN ('PENDING_DISPATCH', 'ACCEPTED'))
              AND (thumbnail_updated_at IS NULL OR thumbnail_updated_at <= :cutoff)
            ORDER BY thumbnail_updated_at ASC NULLS FIRST, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findDueThumbnailSessionIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    Page<StreamSessionEntity> findByVisibilityAndStatusOrderByStartedAtDescIdAsc(
            StreamVisibility visibility,
            StreamSessionStatus status,
            Pageable pageable
    );
}
