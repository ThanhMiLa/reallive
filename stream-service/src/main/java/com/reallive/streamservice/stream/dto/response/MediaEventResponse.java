package com.reallive.streamservice.stream.dto.response;

import com.reallive.streamservice.stream.dto.MediaEventOutcome;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;

import java.util.UUID;

public record MediaEventResponse(
        UUID streamId,
        UUID eventId,
        MediaEventOutcome outcome,
        StreamSessionStatus status
) {}
