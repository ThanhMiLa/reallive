package com.reallive.userservice.user.service;

import com.reallive.userservice.user.dto.response.UserProfileResponse;

import java.util.UUID;

public interface UserService {
    UserProfileResponse getMyProfile(UUID userId);
}
