package com.reallive.channelservice.channel.dto.response;

import java.time.Instant;
import java.util.UUID;

public final class ChatBanResponse {
    private ChatBanResponse() {}

    public record State(
            UUID channelId,
            UUID userId,
            String banType
    ) {}

    public record Item(
            UUID userId,
            String displayName,
            String avatarUrl,
            String banType,
            Instant createdAt
    ) {}

    public record Context(
            UUID channelId,
            UUID ownerUserId,
            boolean canBan,
            boolean canUnban,
            boolean canViewBans
    ) {}
}
