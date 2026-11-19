package com.reallive.streamservice.client.user;

import java.util.UUID;

public record ViewerProfile(
        UUID userId,
        String displayName,
        String avatarUrl
) {}
