package com.reallive.streamservice.stream.adapter.media;

import com.reallive.streamservice.stream.entity.enums.MediaSourceType;

import java.util.UUID;

public record MediaPathStatus(
        String name,
        boolean online,
        UUID sourceId,
        MediaSourceType sourceType
) {}
