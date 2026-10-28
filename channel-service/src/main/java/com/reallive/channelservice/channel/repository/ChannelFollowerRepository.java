package com.reallive.channelservice.channel.repository;

import com.reallive.channelservice.channel.entity.ChannelFollowerEntity;
import com.reallive.channelservice.channel.entity.ChannelFollowerId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ChannelFollowerRepository extends JpaRepository<ChannelFollowerEntity, ChannelFollowerId> {

    /**
     * Uses the composite primary key to atomically reject an existing follow, including concurrent inserts.
     */
    @Modifying
    @Query(value = """
            INSERT INTO {h-schema}channel_followers (channel_id, user_id)
            VALUES (:channelId, :userId)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertFollowIfAbsent(@Param("channelId") UUID channelId, @Param("userId") UUID userId);

    @Modifying
    @Query("""
            DELETE FROM ChannelFollowerEntity follower
            WHERE follower.id.channelId = :channelId
              AND follower.id.userId = :userId
            """)
    int deleteFollow(@Param("channelId") UUID channelId, @Param("userId") UUID userId);
}
