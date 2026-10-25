package com.reallive.channelservice.channel.repository;

import com.reallive.channelservice.channel.entity.ChannelCategoryEntity;
import com.reallive.channelservice.channel.entity.ChannelCategoryId;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChannelCategoryRepository extends JpaRepository<ChannelCategoryEntity, ChannelCategoryId> {

    List<ChannelCategoryEntity> findAllById_ChannelId(UUID channelId);

    @Query("select association from ChannelCategoryEntity association join fetch association.category " +
            "where association.id.channelId = :channelId order by association.category.name, association.category.id")
    List<ChannelCategoryEntity> findCategoriesByChannelId(@Param("channelId") UUID channelId);

    List<ChannelCategoryEntity> findAllById_CategoryId(UUID categoryId);
}
