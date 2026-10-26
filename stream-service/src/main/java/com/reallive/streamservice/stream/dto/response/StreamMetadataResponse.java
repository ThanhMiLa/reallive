package com.reallive.streamservice.stream.dto.response;

import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import java.time.Instant;
import java.util.UUID;

public record StreamMetadataResponse(
        UUID id,
        UUID channelId,
        String title,
        UUID categoryId,
        StreamVisibility visibility,
        StreamSessionStatus status,
        Instant startedAt,
        Instant endedAt,
        Instant reconnectDeadlineAt,
        Instant serverTime
) {}
