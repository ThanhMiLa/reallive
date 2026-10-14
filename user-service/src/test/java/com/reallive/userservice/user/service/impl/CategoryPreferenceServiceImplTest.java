package com.reallive.userservice.user.service.impl;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.dto.response.CategoryPreferencesResponse;
import com.reallive.userservice.user.repository.UserCategoryPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CategoryPreferenceServiceImplTest {

    private UserRepository userRepository;
    private UserCategoryPreferenceRepository userCategoryPreferenceRepository;
    private CategoryPreferenceServiceImpl categoryPreferenceService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userCategoryPreferenceRepository = mock(UserCategoryPreferenceRepository.class);
        categoryPreferenceService = new CategoryPreferenceServiceImpl(userRepository, userCategoryPreferenceRepository);
    }

    @Test
    void getCategoryPreferencesReturnsConfiguredPreferencesForActiveUser() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId)
                .status(UserStatus.ACTIVE)
                .build();
        List<UUID> expectedCategories = List.of(UUID.randomUUID(), UUID.randomUUID());

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userCategoryPreferenceRepository.findCategoryIdsByUserId(userId)).thenReturn(expectedCategories);

        CategoryPreferencesResponse response = categoryPreferenceService.getCategoryPreferences(userId);

        assertNotNull(response);
        assertEquals(expectedCategories, response.getCategoryIds());
        verify(userRepository).findById(userId);
        verify(userCategoryPreferenceRepository).findCategoryIdsByUserId(userId);
    }

    @Test
    void getCategoryPreferencesReturnsEmptyListWhenUserHasNoPreferences() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId)
                .status(UserStatus.ACTIVE)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userCategoryPreferenceRepository.findCategoryIdsByUserId(userId)).thenReturn(List.of());

        CategoryPreferencesResponse response = categoryPreferenceService.getCategoryPreferences(userId);

        assertNotNull(response);
        assertTrue(response.getCategoryIds().isEmpty());
        verify(userRepository).findById(userId);
        verify(userCategoryPreferenceRepository).findCategoryIdsByUserId(userId);
    }

    @Test
    void getCategoryPreferencesThrowsUserNotFoundWhenUserDoesNotExist() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class,
                () -> categoryPreferenceService.getCategoryPreferences(userId));

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
        verify(userRepository).findById(userId);
        verifyNoInteractions(userCategoryPreferenceRepository);
    }

    @Test
    void getCategoryPreferencesThrowsUserNotActiveWhenUserIsBanned() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId)
                .status(UserStatus.BANNED)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        AppException exception = assertThrows(AppException.class,
                () -> categoryPreferenceService.getCategoryPreferences(userId));

        assertEquals(ErrorCode.USER_NOT_ACTIVE, exception.getErrorCode());
        verify(userRepository).findById(userId);
        verifyNoInteractions(userCategoryPreferenceRepository);
    }
}
