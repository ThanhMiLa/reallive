package com.reallive.channelservice.channel.repository;

import com.reallive.channelservice.channel.entity.ChannelUserBanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface ChannelUserBanRepository extends JpaRepository<ChannelUserBanEntity, UUID> {
    @Query(value = """
            SELECT DISTINCT ON (user_id, ban_type) user_id, CAST(user_id AS VARCHAR(36)) AS "userId", ban_type AS "banType", action, created_at, event_order
            FROM channel_user_bans
            WHERE channel_id = :channelId
              AND user_id = :userId
            ORDER BY user_id, ban_type, created_at DESC, event_order DESC
            """, nativeQuery = true)
    List<CurrentBan> findCurrent(@Param("channelId") UUID channelId, @Param("userId") UUID userId);

    interface CurrentBan {
        String getUserId();
        String getBanType();
        String getAction();
    }
}
