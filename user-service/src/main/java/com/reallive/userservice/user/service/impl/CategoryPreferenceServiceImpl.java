package com.reallive.userservice.user.service.impl;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.util.UserValidator;
import com.reallive.userservice.user.dto.response.CategoryPreferencesResponse;
import com.reallive.userservice.user.repository.UserCategoryPreferenceRepository;
import com.reallive.userservice.user.service.CategoryPreferenceService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CategoryPreferenceServiceImpl implements CategoryPreferenceService {

    UserRepository userRepository;
    UserCategoryPreferenceRepository userCategoryPreferenceRepository;

    @Override
    @Transactional(readOnly = true)
    public CategoryPreferencesResponse getCategoryPreferences(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        UserValidator.validateActive(user);

        List<UUID> categoryIds = userCategoryPreferenceRepository.findCategoryIdsByUserId(userId);
        return CategoryPreferencesResponse.builder()
                .categoryIds(categoryIds)
                .build();
    }
}
