package com.reallive.channelservice.channel.repository;

import com.reallive.channelservice.channel.entity.ChannelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ChannelRepository extends JpaRepository<ChannelEntity, UUID> {

    Optional<ChannelEntity> findByStreamerId(UUID streamerId);

    Optional<ChannelEntity> findBySlug(String slug);

    boolean existsByStreamerId(UUID streamerId);

    boolean existsBySlug(String slug);
}
