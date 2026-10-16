package com.reallive.userservice.user.service.impl;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.client.channel.ChannelClientResolver;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.dto.request.UpdateCategoryPreferencesRequest;
import com.reallive.userservice.user.dto.response.CategoryPreferencesResponse;
import com.reallive.userservice.user.entity.UserCategoryPreferenceEntity;
import com.reallive.userservice.user.repository.UserCategoryPreferenceRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CategoryPreferenceServiceImplTest {

    private UserRepository userRepository;
    private UserCategoryPreferenceRepository userCategoryPreferenceRepository;
    private ChannelClientResolver channelClientResolver;
    private CategoryPreferenceServiceImpl categoryPreferenceService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userCategoryPreferenceRepository = mock(UserCategoryPreferenceRepository.class);
        channelClientResolver = mock(ChannelClientResolver.class);
        categoryPreferenceService = new CategoryPreferenceServiceImpl(
                userRepository,
                userCategoryPreferenceRepository,
                channelClientResolver,
                TransactionOperations.withoutTransaction()
        );
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
    void getPreferencesDoesNotRepeatGatewayAccountStatusCheck() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId)
                .status(UserStatus.BANNED)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        Assertions.assertDoesNotThrow(() -> categoryPreferenceService.getCategoryPreferences(userId));
        verify(userCategoryPreferenceRepository).findCategoryIdsByUserId(userId);
    }

    @Test
    void updateCategoryPreferencesThrowsUserNotFoundWhenUserDoesNotExist() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        UpdateCategoryPreferencesRequest request = UpdateCategoryPreferencesRequest.builder()
                .categoryIds(List.of(UUID.randomUUID()))
                .build();

        AppException exception = assertThrows(AppException.class,
                () -> categoryPreferenceService.updateCategoryPreferences(userId, request));

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
        verify(userRepository).findById(userId);
        verifyNoInteractions(channelClientResolver);
        verifyNoInteractions(userCategoryPreferenceRepository);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"BANNED"})
    void updatePreferencesDoesNotRepeatGatewayAccountStatusCheck(UserStatus status) {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId)
                .status(status)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UpdateCategoryPreferencesRequest request = UpdateCategoryPreferencesRequest.builder()
                .categoryIds(List.of(UUID.randomUUID()))
                .build();

        Assertions.assertDoesNotThrow(() -> categoryPreferenceService.updateCategoryPreferences(userId, request));
    }

    @Test
    void updateCategoryPreferencesFailsWhenChannelServiceThrowsCategoryNotFound() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId)
                .status(UserStatus.ACTIVE)
                .build();

        UUID categoryId = UUID.randomUUID();
        UpdateCategoryPreferencesRequest request = UpdateCategoryPreferencesRequest.builder()
                .categoryIds(List.of(categoryId))
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(channelClientResolver.validateCategories(Set.of(categoryId)))
                .thenThrow(new AppException(ErrorCode.CATEGORY_NOT_FOUND));

        AppException exception = assertThrows(AppException.class,
                () -> categoryPreferenceService.updateCategoryPreferences(userId, request));

        assertEquals(ErrorCode.CATEGORY_NOT_FOUND, exception.getErrorCode());
        verify(channelClientResolver).validateCategories(Set.of(categoryId));
        verify(userCategoryPreferenceRepository, never()).deleteAllById_UserId(any());
        verify(userCategoryPreferenceRepository, never()).saveAll(any());
    }

    @Test
    void updateCategoryPreferencesFailsWhenChannelServiceThrowsCategoryNotActive() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId)
                .status(UserStatus.ACTIVE)
                .build();

        UUID categoryId = UUID.randomUUID();
        UpdateCategoryPreferencesRequest request = UpdateCategoryPreferencesRequest.builder()
                .categoryIds(List.of(categoryId))
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(channelClientResolver.validateCategories(Set.of(categoryId)))
                .thenThrow(new AppException(ErrorCode.CATEGORY_NOT_ACTIVE));

        AppException exception = assertThrows(AppException.class,
                () -> categoryPreferenceService.updateCategoryPreferences(userId, request));

        assertEquals(ErrorCode.CATEGORY_NOT_ACTIVE, exception.getErrorCode());
        verify(channelClientResolver).validateCategories(Set.of(categoryId));
        verify(userCategoryPreferenceRepository, never()).deleteAllById_UserId(any());
        verify(userCategoryPreferenceRepository, never()).saveAll(any());
    }

    @Test
    void updateCategoryPreferencesFailsWhenChannelServiceUnavailable() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId)
                .status(UserStatus.ACTIVE)
                .build();

        UUID categoryId = UUID.randomUUID();
        UpdateCategoryPreferencesRequest request = UpdateCategoryPreferencesRequest.builder()
                .categoryIds(List.of(categoryId))
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(channelClientResolver.validateCategories(Set.of(categoryId)))
                .thenThrow(new AppException(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE));

        AppException exception = assertThrows(AppException.class,
                () -> categoryPreferenceService.updateCategoryPreferences(userId, request));

        assertEquals(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE, exception.getErrorCode());
        verify(channelClientResolver).validateCategories(Set.of(categoryId));
        verify(userCategoryPreferenceRepository, never()).deleteAllById_UserId(any());
        verify(userCategoryPreferenceRepository, never()).saveAll(any());
    }

    @Test
    void updateCategoryPreferencesDeduplicatesIdsPreservesOrderAndPersists() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId)
                .status(UserStatus.ACTIVE)
                .build();

        UUID cat1 = UUID.randomUUID();
        UUID cat2 = UUID.randomUUID();
        UUID cat3 = UUID.randomUUID();

        // Input contains duplicates: cat1, cat2, cat1, cat3
        UpdateCategoryPreferencesRequest request = UpdateCategoryPreferencesRequest.builder()
                .categoryIds(List.of(cat1, cat2, cat1, cat3))
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(channelClientResolver.validateCategories(any())).thenReturn(true);

        CategoryPreferencesResponse response = categoryPreferenceService.updateCategoryPreferences(userId, request);

        // Verify Feign validation was passed unique category IDs
        ArgumentCaptor<Set<UUID>> validatedCaptor = ArgumentCaptor.forClass(Set.class);
        verify(channelClientResolver).validateCategories(validatedCaptor.capture());
        assertEquals(Set.of(cat1, cat2, cat3), validatedCaptor.getValue());

        // Verify old preferences were removed and new preferences were flushed and saved
        verify(userCategoryPreferenceRepository).deleteAllById_UserId(userId);
        verify(userCategoryPreferenceRepository).flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserCategoryPreferenceEntity>> entitiesCaptor = ArgumentCaptor.forClass(List.class);
        verify(userCategoryPreferenceRepository).saveAll(entitiesCaptor.capture());

        List<UserCategoryPreferenceEntity> savedEntities = entitiesCaptor.getValue();
        assertEquals(3, savedEntities.size());
        assertEquals(cat1, savedEntities.get(0).getId().getCategoryId());
        assertEquals(userId, savedEntities.get(0).getId().getUserId());
        assertEquals(user, savedEntities.get(0).getUser());
        assertEquals(cat2, savedEntities.get(1).getId().getCategoryId());
        assertEquals(cat3, savedEntities.get(2).getId().getCategoryId());

        // Verify response contains deduplicated list preserving initial order
        assertEquals(List.of(cat1, cat2, cat3), response.getCategoryIds());
    }
}
