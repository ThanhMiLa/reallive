package com.reallive.userservice.user.service.impl;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.client.channel.ChannelClientResolver;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.dto.request.UpdateCategoryPreferencesRequest;
import com.reallive.userservice.user.dto.response.CategoryPreferencesResponse;
import com.reallive.userservice.user.entity.UserCategoryPreferenceEntity;
import com.reallive.userservice.user.entity.UserCategoryPreferenceId;
import com.reallive.userservice.user.repository.UserCategoryPreferenceRepository;
import com.reallive.userservice.user.service.CategoryPreferenceService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CategoryPreferenceServiceImpl implements CategoryPreferenceService {

    UserRepository userRepository;
    UserCategoryPreferenceRepository userCategoryPreferenceRepository;
    ChannelClientResolver channelClientResolver;
    TransactionOperations transactionOperations;

    @Override
    @Transactional(readOnly = true)
    public CategoryPreferencesResponse getCategoryPreferences(UUID userId) {
        findUser(userId);
        List<UUID> categoryIds = userCategoryPreferenceRepository.findCategoryIdsByUserId(userId);
        return CategoryPreferencesResponse.builder()
                .categoryIds(categoryIds)
                .build();
    }

    @Override
    public CategoryPreferencesResponse updateCategoryPreferences(UUID userId, UpdateCategoryPreferencesRequest request) {
        UserEntity user = findUser(userId);
        Set<UUID> uniqueCategoryIds = new LinkedHashSet<>(request.getCategoryIds());
        channelClientResolver.validateCategories(uniqueCategoryIds);
        persistCategoryPreferences(user, uniqueCategoryIds);
        return CategoryPreferencesResponse.builder()
                .categoryIds(new ArrayList<>(uniqueCategoryIds))
                .build();
    }

    private UserEntity findUser(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        return user;
    }

    private void persistCategoryPreferences(UserEntity user, Set<UUID> categoryIds) {
        transactionOperations.executeWithoutResult(status -> {
            userCategoryPreferenceRepository.deleteAllById_UserId(user.getId());
            userCategoryPreferenceRepository.flush();
            List<UserCategoryPreferenceEntity> entities = categoryIds.stream()
                    .map(categoryId -> UserCategoryPreferenceEntity.builder()
                            .id(UserCategoryPreferenceId.builder()
                                    .userId(user.getId())
                                    .categoryId(categoryId)
                                    .build())
                            .user(user)
                            .build())
                    .toList();
            userCategoryPreferenceRepository.saveAll(entities);
        });
    }
}
