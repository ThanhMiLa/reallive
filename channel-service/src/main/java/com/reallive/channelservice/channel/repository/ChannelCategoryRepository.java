package com.reallive.channelservice.channel.repository;

import com.reallive.channelservice.channel.entity.ChannelCategoryEntity;
import com.reallive.channelservice.channel.entity.ChannelCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChannelCategoryRepository extends JpaRepository<ChannelCategoryEntity, ChannelCategoryId> {

    List<ChannelCategoryEntity> findAllById_ChannelId(UUID channelId);

    List<ChannelCategoryEntity> findAllById_CategoryId(UUID categoryId);
}
