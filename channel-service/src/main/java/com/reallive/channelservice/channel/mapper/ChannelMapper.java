package com.reallive.channelservice.channel.mapper;

import com.reallive.channelservice.channel.dto.response.ChannelListResponse;
import com.reallive.channelservice.channel.dto.response.ChannelResponse;
import com.reallive.channelservice.channel.dto.response.CategorySummaryResponse;
import com.reallive.channelservice.channel.dto.response.OwnChannelResponse;
import com.reallive.channelservice.channel.entity.CategoryEntity;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface ChannelMapper {

    @Mapping(target = "numberOfFollower", source = "followerCount")
    ChannelListResponse toListResponse(ChannelEntity channel);

    @Mapping(target = "categoryIds", source = "categoryIds")
    ChannelResponse toResponse(ChannelEntity channel, List<UUID> categoryIds);

    @Mapping(target = "numberOfFollower", source = "channel.followerCount")
    @Mapping(target = "categories", source = "categories")
    OwnChannelResponse toOwnChannelResponse(ChannelEntity channel, List<CategoryEntity> categories);

    CategorySummaryResponse toSummaryResponse(CategoryEntity category);
}
