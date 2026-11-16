package com.reallive.channelservice.channel.repository;

import com.reallive.channelservice.channel.entity.ChannelUserBanEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ChannelUserBanRepository extends JpaRepository<ChannelUserBanEntity, UUID> {
    Optional<ChannelUserBanEntity> findByChannelIdAndUserId(UUID channelId, UUID userId);

    Page<ChannelUserBanEntity> findByChannelId(UUID channelId, Pageable pageable);
}
