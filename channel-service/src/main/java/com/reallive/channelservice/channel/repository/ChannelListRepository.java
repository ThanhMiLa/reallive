package com.reallive.channelservice.channel.repository;

import com.reallive.channelservice.channel.dto.request.FollowingSortBy;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

public interface ChannelListRepository {

    Page<ChannelEntity> findFollowingChannels(Specification<ChannelEntity> specification, UUID userId,
            FollowingSortBy sortBy, Sort.Direction direction, Pageable pageable);

}
