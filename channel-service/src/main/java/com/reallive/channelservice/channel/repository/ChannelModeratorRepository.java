package com.reallive.channelservice.channel.repository;

import com.reallive.channelservice.channel.entity.ChannelModeratorEntity;
import com.reallive.channelservice.channel.entity.ChannelModeratorId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelModeratorRepository extends JpaRepository<ChannelModeratorEntity, ChannelModeratorId> {}
