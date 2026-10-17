package com.reallive.channelservice.channel.repository;

import com.reallive.channelservice.channel.entity.ChannelEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ChannelRepository extends JpaRepository<ChannelEntity, UUID>, JpaSpecificationExecutor<ChannelEntity> {

    Optional<ChannelEntity> findByStreamerId(UUID streamerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select channel from ChannelEntity channel where channel.streamerId = :streamerId")
    Optional<ChannelEntity> findByStreamerIdForUpdate(@Param("streamerId") UUID streamerId);

    Optional<ChannelEntity> findBySlug(String slug);

    boolean existsByStreamerId(UUID streamerId);

    /**
     * Checks the unique streamer ownership across visible and soft-deleted channel records.
     */
    @Query(value = "select exists(select 1 from {h-schema}channels " +
            "where streamer_id = :streamerId)",
            nativeQuery = true)
    boolean existsByStreamerIdIncludingDeleted(@Param("streamerId") UUID streamerId);

    boolean existsBySlug(String slug);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value =
            "UPDATE {h-schema}channels " +
            "SET follower_count = follower_count + 1 " +
            "WHERE id = :channelId AND deleted_at IS NULL",
            nativeQuery = true)
    int incrementFollowerCount(@Param("channelId") UUID channelId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value =
            "UPDATE {h-schema}channels " +
            "SET follower_count = follower_count - 1 " +
            "WHERE id = :channelId AND deleted_at IS NULL AND follower_count > 0", nativeQuery = true)
    int decrementFollowerCount(@Param("channelId") UUID channelId);
}
