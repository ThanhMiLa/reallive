package com.reallive.channelservice.channel.dto.response;

import com.reallive.channelservice.channel.entity.enums.ChannelBanType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChannelBanResponse {
    private ChannelBanResponse() {}

    public record State(
            UUID channelId,
            UUID userId,
            ChannelBanType banType,
            List<ChannelBanType> allowedBanTypes,
            boolean canUnban
    ) {}

    public record Item(
            UUID userId,
            String displayName,
            String avatarUrl,
            ChannelBanType banType,
            Instant createdAt,
            List<ChannelBanType> allowedBanTypes,
            boolean canUnban
    ) {}

    public record Context(
            UUID channelId,
            UUID ownerUserId,
            List<ChannelBanType> allowedBanTypes,
            List<ChannelBanType> allowedUnbanTypes,
            boolean canViewBans
    ) {}
}
