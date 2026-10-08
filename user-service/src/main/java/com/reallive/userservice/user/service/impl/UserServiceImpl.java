package com.reallive.userservice.user.service.impl;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.util.UserValidator;
import com.reallive.userservice.user.dto.response.UserProfileResponse;
import com.reallive.userservice.user.entity.ProfileEntity;
import com.reallive.userservice.user.repository.ProfileRepository;
import com.reallive.userservice.user.service.UserService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserServiceImpl implements UserService {
    ProfileRepository profileRepository;

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getMyProfile(UUID userId) {
        ProfileEntity profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.PROFILE_NOT_FOUND));
        UserEntity user = profile.getUser();
        UserValidator.validateActive(user);

        return UserProfileResponse.builder()
                .userId(user.getId())
                .displayName(profile.getDisplayName())
                .email(user.getEmail())
                .avatarUrl(profile.getAvatarUrl())
                .dob(profile.getDob())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
