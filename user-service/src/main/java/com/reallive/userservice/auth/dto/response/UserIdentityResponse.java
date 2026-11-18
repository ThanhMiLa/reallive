package com.reallive.userservice.auth.dto.response;

import com.reallive.userservice.auth.entity.UserStatus;

import java.util.List;
import java.util.UUID;

public record UserIdentityResponse(
        UUID userId,
        String displayName,
        String avatarUrl,
        UserStatus status
) {
    public record Page(
            List<UserIdentityResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
