package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.dto.request.CreateChannelRequest;
import com.reallive.channelservice.channel.dto.request.UpdateChannelRequest;
import com.reallive.channelservice.channel.dto.response.ChannelResponse;
import com.reallive.channelservice.channel.client.streamer.ActiveStreamerResolver;
import com.reallive.channelservice.channel.entity.CategoryEntity;
import com.reallive.channelservice.channel.entity.ChannelCategoryEntity;
import com.reallive.channelservice.channel.entity.ChannelCategoryId;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.enums.CategoryStatus;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.mapper.ChannelMapper;
import com.reallive.channelservice.channel.repository.CategoryRepository;
import com.reallive.channelservice.channel.repository.ChannelCategoryRepository;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.service.ChannelService;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChannelServiceImpl implements ChannelService {

    ChannelRepository channelRepository;
    CategoryRepository categoryRepository;
    ChannelCategoryRepository channelCategoryRepository;
    ActiveStreamerResolver activeStreamerResolver;
    ChannelMapper channelMapper;

    @Override
    @Transactional
    public boolean createChannel(CreateChannelRequest request) {
        if (channelRepository.existsByStreamerIdIncludingDeleted(request.getStreamerId())) {
            throw new AppException(ErrorCode.CHANNEL_ALREADY_EXISTED);
        }
        ChannelEntity channelEntity = ChannelEntity.builder()
                .name(request.getName())
                .streamerId(request.getStreamerId())
                .slug("channel-" + request.getStreamerId())
                .avatarUrl(request.getAvatarUrl() != null ? request.getAvatarUrl() : "")
                .status(ChannelStatus.ACTIVE)
                .build();
        try {
            ChannelEntity channel = channelRepository.saveAndFlush(channelEntity);
            return channel.getId() != null;
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.CHANNEL_DATA_UNAVAILABLE);
        }
    }

    @Override
    @Transactional
    public ChannelResponse updateChannel(UUID userId, UpdateChannelRequest request) {
        UUID streamerId = activeStreamerResolver.getActiveStreamerIdByUserId(userId);
        ChannelEntity channel = findEditableChannel(streamerId);
        Set<UUID> categoryIds = new LinkedHashSet<>(request.getCategoryIds());
        List<CategoryEntity> categories = findActiveCategories(categoryIds);

        channel.setDescription(request.getDescription().isEmpty() ? null : request.getDescription());
        channel.setBannerUrl(request.getBannerUrl().isEmpty() ? null : request.getBannerUrl());
        updateCategories(channel, categories, categoryIds);
        channelRepository.save(channel);

        return channelMapper.toResponse(channel, List.copyOf(categoryIds));
    }

    private ChannelEntity findEditableChannel(UUID streamerId) {
        ChannelEntity channel = channelRepository.findByStreamerIdForUpdate(streamerId)
                .orElseThrow(() -> new AppException(ErrorCode.CHANNEL_NOT_FOUND));
        if (channel.getStatus() != ChannelStatus.ACTIVE) {
            throw new AppException(ErrorCode.CHANNEL_NOT_ACTIVE);
        }
        return channel;
    }

    private List<CategoryEntity> findActiveCategories(Set<UUID> categoryIds) {
        List<CategoryEntity> categories = categoryRepository.findAllById(categoryIds);
        if (categories.size() != categoryIds.size()) {
            throw new AppException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        if (categories.stream().anyMatch(category -> category.getStatus() != CategoryStatus.ACTIVE)) {
            throw new AppException(ErrorCode.CATEGORY_NOT_ACTIVE);
        }
        return categories;
    }

    private void updateCategories(ChannelEntity channel, List<CategoryEntity> categories, Set<UUID> categoryIds) {
        List<ChannelCategoryEntity> existingCategories = channelCategoryRepository.findAllById_ChannelId(channel.getId());
        Set<UUID> existingCategoryIds = existingCategories.stream()
                .map(association -> association.getId().getCategoryId())
                .collect(Collectors.toSet());
        List<ChannelCategoryEntity> removedCategories = existingCategories.stream()
                .filter(association -> !categoryIds.contains(association.getId().getCategoryId()))
                .toList();
        List<ChannelCategoryEntity> addedCategories = categories.stream()
                .filter(category -> !existingCategoryIds.contains(category.getId()))
                .map(category -> ChannelCategoryEntity.builder()
                        .id(new ChannelCategoryId(channel.getId(), category.getId()))
                        .channel(channel)
                        .category(category)
                        .build())
                .toList();

        channelCategoryRepository.deleteAll(removedCategories);
        channelCategoryRepository.saveAll(addedCategories);
    }
}

