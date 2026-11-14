package com.reallive.streamservice.stream.dto.response;

import com.reallive.streamservice.stream.entity.enums.ChannelStreamConfigStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;

import java.util.UUID;

/** Channel settings contain no publish or relay credentials. */
public record StreamSetupResponse(
        UUID channelId,
        String title,
        UUID categoryId,
        StreamVisibility visibility,
        ChannelStreamConfigStatus status,
        boolean hasStreamKey,
        String blockedReason
) {}
