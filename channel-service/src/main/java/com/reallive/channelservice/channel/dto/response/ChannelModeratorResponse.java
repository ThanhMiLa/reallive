package com.reallive.channelservice.channel.dto.response;

import java.time.Instant;
import java.util.UUID;

public final class ChannelModeratorResponse {

    private ChannelModeratorResponse() {}

    public record Item(
            UUID userId,
            String displayName,
            String avatarUrl,
            Instant assignedAt
    ) {}

    public record Candidate(
            UUID userId,
            String displayName,
            String avatarUrl,
            Eligibility eligibility
    ) {}

    public enum Eligibility {
        ELIGIBLE,
        CHANNEL_OWNER,
        ALREADY_MODERATOR,
        CHANNEL_BANNED,
        ACCOUNT_INACTIVE
    }
}
