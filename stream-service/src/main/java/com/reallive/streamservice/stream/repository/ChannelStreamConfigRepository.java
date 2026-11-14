package com.reallive.streamservice.stream.repository;

import com.reallive.streamservice.stream.entity.ChannelStreamConfigEntity;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChannelStreamConfigRepository
        extends JpaRepository<ChannelStreamConfigEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            SELECT c
            FROM ChannelStreamConfigEntity c
            WHERE c.channelId = :channelId
            """)
    Optional<ChannelStreamConfigEntity> findByIdForUpdate(@Param("channelId") UUID channelId);

    Optional<ChannelStreamConfigEntity> findByOwnerUserId(UUID ownerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            SELECT c
            FROM ChannelStreamConfigEntity c
            WHERE c.ownerUserId = :ownerUserId
            """)
    Optional<ChannelStreamConfigEntity> findByOwnerUserIdForUpdate(
            @Param("ownerUserId") UUID ownerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            SELECT c
            FROM ChannelStreamConfigEntity c
            WHERE c.currentIngestKeyId = :ingestKeyId
            """)
    Optional<ChannelStreamConfigEntity> findByIngestKeyIdForUpdate(
            @Param("ingestKeyId") UUID ingestKeyId);

    Optional<ChannelStreamConfigEntity> findByCurrentIngestKeyId(UUID ingestKeyId);
}
