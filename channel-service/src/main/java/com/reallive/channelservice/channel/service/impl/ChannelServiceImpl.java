package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.dto.request.CreateChannelRequest;
import com.reallive.channelservice.channel.dto.request.UpdateChannelProfileRequest;
import com.reallive.channelservice.channel.dto.request.UpdateChannelRequest;
import com.reallive.channelservice.channel.dto.response.ChannelResponse;
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
import com.reallive.channelservice.channel.util.BannerValidator.BannerImage;
import com.reallive.channelservice.channel.util.BannerValidator;
import com.reallive.channelservice.client.streamer.ActiveStreamerResolver;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import com.reallive.channelservice.common.service.R2StorageService;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChannelServiceImpl implements ChannelService {

    ChannelRepository channelRepository;
    CategoryRepository categoryRepository;
    ChannelCategoryRepository channelCategoryRepository;
    ActiveStreamerResolver activeStreamerResolver;
    ChannelMapper channelMapper;
    R2StorageService r2StorageService;

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
        Set<UUID> requestedCategoryIds = request.getCategoryIds() == null
                ? null : new LinkedHashSet<>(request.getCategoryIds());
        List<CategoryEntity> categories = requestedCategoryIds == null
                ? List.of() : findActiveCategories(requestedCategoryIds);

        if (request.getDescription() != null) {
            channel.setDescription(request.getDescription());
        }
        if (request.getBannerUrl() != null) {
            updateBanner(channel, request.getBannerUrl());
        }
        if (requestedCategoryIds != null) {
            updateCategories(channel, categories, requestedCategoryIds);
        }
        channelRepository.save(channel);

        List<UUID> categoryIds = requestedCategoryIds == null
                ? channelCategoryRepository.findAllById_ChannelId(channel.getId()).stream()
                        .map(association -> association.getId().getCategoryId()).toList()
                : List.copyOf(requestedCategoryIds);
        return channelMapper.toResponse(channel, categoryIds);
    }

    @Override
    @Transactional
    public boolean updateChannelProfile(UUID streamerId, UpdateChannelProfileRequest request) {
        ChannelEntity channel = channelRepository.findByStreamerIdForUpdate(streamerId)
                .orElseThrow(() -> new AppException(ErrorCode.CHANNEL_NOT_FOUND));
        if (request.getName() != null) {
            channel.setName(request.getName().strip());
        }
        if (request.getAvatarUrl() != null) {
            channel.setAvatarUrl(request.getAvatarUrl());
        }
        channelRepository.save(channel);
        return true;
    }

    private void updateBanner(ChannelEntity channel, MultipartFile banner) {
        String oldBannerUrl = channel.getBannerUrl();
        BannerImage image = BannerValidator.validate(banner);
        String objectKey = "channels/" + channel.getId() + "/banners/" + UUID.randomUUID() + "." + image.extension();
        String newBannerUrl = r2StorageService.getPublicUrl(objectKey);
        if (newBannerUrl.length() > 500) {
            throw new AppException(ErrorCode.BANNER_STORAGE_UNAVAILABLE);
        }
        registerBannerCleanup(channel.getId(), objectKey, oldBannerUrl);
        r2StorageService.upload(objectKey, image.content(), image.contentType());
        channel.setBannerUrl(newBannerUrl);
    }

    /** Compensates R2 operations after the outcome of the local database transaction is known. */
    private void registerBannerCleanup(UUID channelId, String newObjectKey, String oldBannerUrl) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    if (status == STATUS_COMMITTED) {
                        r2StorageService.deleteManagedBanner(oldBannerUrl, channelId);
                    } else if (status == STATUS_ROLLED_BACK && newObjectKey != null) {
                        r2StorageService.deleteQuietly(newObjectKey);
                    } else if (status == STATUS_UNKNOWN) {
                        log.warn("Transaction outcome is unknown for channel banner {}; cleanup may be required", channelId);
                    }
                } catch (RuntimeException exception) {
                    log.warn("Banner cleanup failed for channel {}; retry cleanup is required", channelId);
                }
            }
        });
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

