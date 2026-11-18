package com.reallive.channelservice.channel.repository;

import com.reallive.channelservice.channel.entity.ChannelUserBanEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelUserBanRepository extends JpaRepository<ChannelUserBanEntity, UUID> {
    Optional<ChannelUserBanEntity> findByChannelIdAndUserId(UUID channelId, UUID userId);

    boolean existsByChannelIdAndUserId(UUID channelId, UUID userId);

    Page<ChannelUserBanEntity> findByChannelId(UUID channelId, Pageable pageable);

    @Query("""
            SELECT entry.userId
            FROM ChannelUserBanEntity entry
            WHERE entry.channelId = :channelId
              AND entry.userId IN :userIds
            """)
    List<UUID> findUserIdsForChannel(UUID channelId, Collection<UUID> userIds);
}
