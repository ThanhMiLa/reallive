package com.reallive.userservice.user.dto.response;

import java.util.UUID;

public record ChatProfileSnapshot(
        UUID userId,
        String displayName,
        String avatarUrl
) {}
