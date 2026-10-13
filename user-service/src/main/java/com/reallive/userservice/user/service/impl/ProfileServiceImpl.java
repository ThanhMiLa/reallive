package com.reallive.userservice.user.service.impl;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.client.channel.ChannelClientResolver;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.service.R2StorageService;
import com.reallive.userservice.common.util.UserValidator;
import com.reallive.userservice.streamer.entity.StreamerEntity;
import com.reallive.userservice.streamer.repository.StreamerRepository;
import com.reallive.userservice.user.dto.request.UpdateProfileRequest;
import com.reallive.userservice.user.dto.response.UserProfileResponse;
import com.reallive.userservice.user.entity.ProfileEntity;
import com.reallive.userservice.user.mapper.ProfileMapper;
import com.reallive.userservice.user.repository.ProfileRepository;
import com.reallive.userservice.user.service.ProfileService;
import com.reallive.userservice.user.util.AvatarValidator;
import com.reallive.userservice.user.util.AvatarValidator.AvatarImage;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ProfileServiceImpl implements ProfileService {
    ProfileRepository profileRepository;
    R2StorageService r2StorageService;
    ProfileMapper profileMapper;
    StreamerRepository streamerRepository;
    ChannelClientResolver channelClientResolver;

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getMyProfile(UUID userId) {
        ProfileEntity profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.PROFILE_NOT_FOUND));
        UserEntity user = profile.getUser();
        UserValidator.validateActive(user);

        return profileMapper.toResponse(profile);
    }

    @Override
    @Transactional
    public UserProfileResponse updateMyProfile(UUID userId, UpdateProfileRequest request) {
        if (request.getDisplayName() == null && request.getDob() == null && request.getAvatar() == null) {
            throw new AppException(ErrorCode.PROFILE_UPDATE_EMPTY);
        }
        AvatarImage avatar = request.getAvatar() == null ? null : AvatarValidator.validate(request.getAvatar());
        ProfileEntity profile = profileRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new AppException(ErrorCode.PROFILE_NOT_FOUND));
        UserValidator.validateActive(profile.getUser());

        if (request.getDisplayName() != null) {
            profile.setDisplayName(request.getDisplayName().strip());
        }
        if (request.getDob() != null) {
            profile.setDob(request.getDob());
        }
        if (avatar != null) {
            replaceAvatar(profile, userId, avatar);
        }
        profileRepository.save(profile);
        return profileMapper.toResponse(profile);
    }

    private void replaceAvatar(ProfileEntity profile, UUID userId, AvatarImage avatar) {
        String objectKey = "avatars/" + userId + "/" + UUID.randomUUID() + "." + avatar.extension();
        String newAvatarUrl = r2StorageService.getPublicUrl(objectKey);
        String oldAvatarUrl = profile.getAvatarUrl();
        if (newAvatarUrl.length() > 500) {
            throw new IllegalStateException("Configured avatar URL exceeds the database column length");
        }
        registerAvatarCleanup(userId, objectKey, oldAvatarUrl);
        r2StorageService.upload(objectKey, avatar.content(), avatar.contentType());
        profile.setAvatarUrl(newAvatarUrl);

        streamerRepository.findByUserId(userId)
                .ifPresent(streamer -> channelClientResolver.updateChannelAvatar(streamer.getId(), newAvatarUrl));
    }

    /** R2 operations are compensated after the database transaction outcome is known. */
    private void registerAvatarCleanup(UUID userId, String objectKey, String oldAvatarUrl) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    if (status == STATUS_COMMITTED) {
                        r2StorageService.deleteManagedAvatar(oldAvatarUrl, userId);
                    } else if (status == STATUS_ROLLED_BACK) {
                        r2StorageService.deleteQuietly(objectKey);
                    } else {
                        log.warn("Transaction outcome is unknown for avatar {}; manual cleanup may be required", objectKey);
                    }
                } catch (RuntimeException exception) {
                    log.warn("Avatar cleanup failed for user {}; retry cleanup is required", userId);
                }
            }
        });
    }

}
