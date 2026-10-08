package com.reallive.userservice.user.service.impl;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.dto.response.UserProfileResponse;
import com.reallive.userservice.user.entity.ProfileEntity;
import com.reallive.userservice.user.repository.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    private ProfileRepository profileRepository;
    private UserServiceImpl userService;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ProfileRepository.class);
        userService = new UserServiceImpl(profileRepository);
        user = UserEntity.builder()
                .id(UUID.randomUUID())
                .email("viewer@example.com")
                .build();
        user.setCreatedAt(LocalDateTime.of(2026, 10, 1, 8, 30));
    }

    @Test
    void getMyProfileReturnsAccountAndProfileDetails() {
        ProfileEntity profile = ProfileEntity.builder()
                .id(UUID.randomUUID())
                .user(user)
                .displayName("Viewer")
                .avatarUrl("https://example.com/avatar.png")
                .dob(LocalDate.of(2000, 1, 1))
                .build();
        profile.setCreatedAt(LocalDateTime.of(2026, 10, 2, 9, 0));
        when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));

        UserProfileResponse response = userService.getMyProfile(user.getId());

        assertEquals(user.getId(), response.getUserId());
        assertEquals("Viewer", response.getDisplayName());
        assertEquals("viewer@example.com", response.getEmail());
        assertEquals("https://example.com/avatar.png", response.getAvatarUrl());
        assertEquals(LocalDate.of(2000, 1, 1), response.getDob());
        assertEquals(user.getCreatedAt(), response.getCreatedAt());
        verify(profileRepository).findByUserId(user.getId());
    }

    @Test
    void getMyProfilePreservesEmptyOptionalFields() {
        ProfileEntity profile = ProfileEntity.builder().user(user).displayName("Viewer").build();
        when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));

        UserProfileResponse response = userService.getMyProfile(user.getId());

        assertNull(response.getAvatarUrl());
        assertNull(response.getDob());
    }

    @Test
    void getMyProfileThrowsNotFoundWhenProfileIsMissing() {
        when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> userService.getMyProfile(user.getId()));

        assertEquals(ErrorCode.PROFILE_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void getMyProfileRejectsBannedAccounts() {
        user.setStatus(UserStatus.BANNED);
        ProfileEntity profile = ProfileEntity.builder().user(user).displayName("Viewer").build();
        when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));

        AppException exception = assertThrows(AppException.class, () -> userService.getMyProfile(user.getId()));

        assertEquals(ErrorCode.USER_NOT_ACTIVE, exception.getErrorCode());
    }
}
