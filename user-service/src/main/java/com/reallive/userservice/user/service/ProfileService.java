package com.reallive.userservice.user.service;

import com.reallive.userservice.user.dto.request.UpdateProfileRequest;
import com.reallive.userservice.user.dto.response.UserProfileResponse;

import java.util.UUID;

public interface ProfileService {
    UserProfileResponse getMyProfile(UUID userId);

    UserProfileResponse updateMyProfile(UUID userId, UpdateProfileRequest request);
}
