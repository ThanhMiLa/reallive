package com.reallive.userservice.user.service.impl;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.service.R2StorageService;
import com.reallive.userservice.user.dto.request.UpdateProfileRequest;
import com.reallive.userservice.user.dto.response.UserProfileResponse;
import com.reallive.userservice.user.entity.ProfileEntity;
import com.reallive.userservice.user.mapper.ProfileMapper;
import com.reallive.userservice.user.repository.ProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProfileServiceImplTest {

    private ProfileRepository profileRepository;
    private ProfileServiceImpl profileService;
    private R2StorageService storage;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ProfileRepository.class);
        storage = mock(R2StorageService.class);
        profileService = new ProfileServiceImpl(profileRepository, storage, Mappers.getMapper(ProfileMapper.class));
        user = UserEntity.builder()
                .id(UUID.randomUUID())
                .email("viewer@example.com")
                .build();
        user.setCreatedAt(LocalDateTime.of(2026, 10, 1, 8, 30));
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void updateRejectsEmptyRequestsBeforeAccessingStorageOrDatabase() {
        AppException exception = assertThrows(AppException.class,
                () -> profileService.updateMyProfile(user.getId(), new UpdateProfileRequest()));

        assertEquals(ErrorCode.PROFILE_UPDATE_EMPTY, exception.getErrorCode());
        verifyNoInteractions(profileRepository, storage);
    }

    @Test
    void updateRejectsFakeImagesBeforeUploading() {
        UpdateProfileRequest request = UpdateProfileRequest.builder()
                .avatar(new MockMultipartFile("avatar", "fake.png", "image/png", new byte[] {1, 2, 3}))
                .build();

        AppException exception = assertThrows(AppException.class,
                () -> profileService.updateMyProfile(user.getId(), request));

        assertEquals(ErrorCode.AVATAR_INVALID, exception.getErrorCode());
        verifyNoInteractions(profileRepository, storage);
    }

    @Test
    void updateDisplayNamePreservesOmittedFields() {
        ProfileEntity profile = profileForUpdate();
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setDisplayName("  New Viewer  ");

        UserProfileResponse response = profileService.updateMyProfile(user.getId(), request);

        assertEquals("New Viewer", response.getDisplayName());
        assertEquals(profile.getAvatarUrl(), response.getAvatarUrl());
        assertEquals(LocalDate.of(2000, 1, 1), response.getDob());
        assertEquals(user.getEmail(), response.getEmail());
        assertEquals(user.getCreatedAt(), response.getCreatedAt());
        verify(profileRepository).save(profile);
        verifyNoInteractions(storage);
    }

    @Test
    void updateDobPreservesDisplayNameAndAvatar() {
        ProfileEntity profile = profileForUpdate();
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setDob(LocalDate.of(2001, 2, 3));

        UserProfileResponse response = profileService.updateMyProfile(user.getId(), request);

        assertEquals(LocalDate.of(2001, 2, 3), response.getDob());
        assertEquals("Viewer", response.getDisplayName());
        assertEquals(profile.getAvatarUrl(), response.getAvatarUrl());
        verifyNoInteractions(storage);
    }

    @Test
    void updateRejectsMissingProfile() {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setDisplayName("Viewer");
        when(profileRepository.findByUserIdForUpdate(user.getId())).thenReturn(Optional.empty());
        AppException exception = assertThrows(AppException.class, () -> profileService.updateMyProfile(user.getId(), request));
        assertEquals(ErrorCode.PROFILE_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(storage);
    }

    @Test
    void updateRejectsBannedAccountsBeforeUploading() throws Exception {
        profileForUpdate();
        user.setStatus(UserStatus.BANNED);
        AppException exception = assertThrows(AppException.class,
                () -> profileService.updateMyProfile(user.getId(), avatarRequest()));
        assertEquals(ErrorCode.USER_NOT_ACTIVE, exception.getErrorCode());
        verifyNoInteractions(storage);
        verify(profileRepository, never()).save(any());
    }

    @Test
    void updateUploadsNewAvatarAndDeletesOldOnlyAfterCommit() throws Exception {
        ProfileEntity profile = profileForUpdate();
        String oldUrl = profile.getAvatarUrl();
        prepareAvatarTransaction();

        UserProfileResponse response = profileService.updateMyProfile(user.getId(), avatarRequest());

        assertTrue(response.getAvatarUrl().matches("https://images.example.com/avatars/" + user.getId() + "/[0-9a-f-]+\\.png"));
        verify(storage).upload(anyString(), any(byte[].class), eq("image/png"));
        verify(storage, never()).deleteManagedAvatar(any(), any());
        verify(storage, never()).deleteQuietly(anyString());
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage).deleteManagedAvatar(oldUrl, user.getId());
        verify(storage, never()).deleteQuietly(anyString());
    }

    @Test
    void updateDeletesNewUploadWhenDatabaseSaveFails() throws Exception {
        ProfileEntity profile = profileForUpdate();
        prepareAvatarTransaction();
        doThrow(new IllegalStateException("Database failure")).when(profileRepository).save(profile);

        assertThrows(IllegalStateException.class, () -> profileService.updateMyProfile(user.getId(), avatarRequest()));
        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(storage).deleteQuietly(profile.getAvatarUrl().substring("https://images.example.com/".length()));
        verify(storage, never()).deleteManagedAvatar(any(), any());
    }

    @Test
    void updateDoesNotSaveWhenUploadFailsAndAttemptsCleanup() throws Exception {
        ProfileEntity profile = profileForUpdate();
        String oldUrl = profile.getAvatarUrl();
        prepareAvatarTransaction();
        doThrow(new AppException(ErrorCode.AVATAR_UPLOAD_FAILED)).when(storage)
                .upload(anyString(), any(byte[].class), anyString());

        AppException exception = assertThrows(AppException.class,
                () -> profileService.updateMyProfile(user.getId(), avatarRequest()));
        assertEquals(ErrorCode.AVATAR_UPLOAD_FAILED, exception.getErrorCode());
        assertEquals(oldUrl, profile.getAvatarUrl());
        verify(profileRepository, never()).save(any());
        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage).deleteQuietly(anyString());
        verify(storage, never()).deleteManagedAvatar(any(), any());
    }

    @Test
    void updatePreservesBothFilesWhenTransactionOutcomeIsUnknown() throws Exception {
        profileForUpdate();
        prepareAvatarTransaction();
        profileService.updateMyProfile(user.getId(), avatarRequest());
        completeTransaction(TransactionSynchronization.STATUS_UNKNOWN);
        verify(storage, never()).deleteManagedAvatar(any(), any());
        verify(storage, never()).deleteQuietly(anyString());
    }

    @Test
    void updateDoesNotFailCommittedResponseWhenOldAvatarCleanupFails() throws Exception {
        profileForUpdate();
        prepareAvatarTransaction();
        doThrow(new IllegalStateException("Cleanup failure")).when(storage).deleteManagedAvatar(any(), any());
        profileService.updateMyProfile(user.getId(), avatarRequest());
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage, never()).deleteQuietly(anyString());
    }

    @Test
    void updateAvatarPreservesOtherFieldsWhenNoPreviousAvatarExists() throws Exception {
        ProfileEntity profile = profileForUpdate();
        profile.setAvatarUrl(null);
        prepareAvatarTransaction();

        UserProfileResponse response = profileService.updateMyProfile(user.getId(), avatarRequest());

        assertEquals("Viewer", response.getDisplayName());
        assertEquals(LocalDate.of(2000, 1, 1), response.getDob());
        assertEquals(user.getId(), response.getUserId());
        verify(profileRepository).save(profile);
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage).deleteManagedAvatar(null, user.getId());
        verify(storage, never()).deleteQuietly(anyString());
    }

    @Test
    void updateRejectsAnAvatarUrlThatCannotFitInTheDatabaseBeforeUploading() throws Exception {
        ProfileEntity profile = profileForUpdate();
        String oldUrl = profile.getAvatarUrl();
        when(storage.getPublicUrl(anyString())).thenReturn("https://images.example.com/" + "x".repeat(500));

        assertThrows(IllegalStateException.class,
                () -> profileService.updateMyProfile(user.getId(), avatarRequest()));

        assertEquals(oldUrl, profile.getAvatarUrl());
        verify(storage, never()).upload(anyString(), any(byte[].class), anyString());
        verify(profileRepository, never()).save(any());
    }

    @Test
    void updateDoesNotMaskRollbackWhenNewAvatarCleanupFails() throws Exception {
        profileForUpdate();
        prepareAvatarTransaction();
        profileService.updateMyProfile(user.getId(), avatarRequest());
        doThrow(new IllegalStateException("Cleanup failure")).when(storage).deleteQuietly(anyString());

        assertDoesNotThrow(() -> completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(storage).deleteQuietly(anyString());
        verify(storage, never()).deleteManagedAvatar(any(), any());
    }

    private ProfileEntity profileForUpdate() {
        ProfileEntity profile = ProfileEntity.builder().user(user).displayName("Viewer")
                .avatarUrl("https://images.example.com/old.png").dob(LocalDate.of(2000, 1, 1)).build();
        when(profileRepository.findByUserIdForUpdate(user.getId())).thenReturn(Optional.of(profile));
        return profile;
    }

    private UpdateProfileRequest avatarRequest() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setAvatar(new MockMultipartFile("avatar", "../../untrusted.jpg", "image/jpeg", output.toByteArray()));
        return request;
    }

    private void prepareAvatarTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        when(storage.getPublicUrl(anyString())).thenAnswer(invocation -> "https://images.example.com/" + invocation.getArgument(0));
    }

    private void completeTransaction(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(callback -> callback.afterCompletion(status));
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

        UserProfileResponse response = profileService.getMyProfile(user.getId());

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

        UserProfileResponse response = profileService.getMyProfile(user.getId());

        assertNull(response.getAvatarUrl());
        assertNull(response.getDob());
    }

    @Test
    void getMyProfileThrowsNotFoundWhenProfileIsMissing() {
        when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> profileService.getMyProfile(user.getId()));

        assertEquals(ErrorCode.PROFILE_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void getMyProfileRejectsBannedAccounts() {
        user.setStatus(UserStatus.BANNED);
        ProfileEntity profile = ProfileEntity.builder().user(user).displayName("Viewer").build();
        when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.of(profile));

        AppException exception = assertThrows(AppException.class, () -> profileService.getMyProfile(user.getId()));

        assertEquals(ErrorCode.USER_NOT_ACTIVE, exception.getErrorCode());
    }
}
