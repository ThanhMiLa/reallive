package com.reallive.streamservice.stream.adapter.media.dto;

import com.reallive.streamservice.stream.entity.enums.MediaSourceType;

import java.time.Instant;
import java.util.UUID;

public record MediaPathStatus(
        String name,
        boolean online,
        UUID sourceId,
        MediaSourceType sourceType,
        Instant onlineTime
) {
    public MediaPathStatus(String name, boolean online, UUID sourceId, MediaSourceType sourceType) {
        this(name, online, sourceId, sourceType, null);
    }
}
