package com.reallive.channelservice.channel.repository;

import com.reallive.channelservice.channel.entity.ChannelModeratorEntity;
import com.reallive.channelservice.channel.entity.ChannelModeratorId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ChannelModeratorRepository extends JpaRepository<ChannelModeratorEntity, ChannelModeratorId> {
    Page<ChannelModeratorEntity> findByIdChannelId(UUID channelId, Pageable pageable);

    @Query("""
            SELECT entry.id.userId
            FROM ChannelModeratorEntity entry
            WHERE entry.id.channelId = :channelId
              AND entry.id.userId IN :userIds
            """)
    List<UUID> findUserIdsForChannel(UUID channelId, Collection<UUID> userIds);
}
