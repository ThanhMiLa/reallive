package com.reallive.streamservice.stream.dto;

import com.reallive.streamservice.stream.entity.StreamSessionEntity;

public record SessionCreationResult(
        StreamSessionEntity session,
        boolean created
) {}
