package com.reallive.streamservice.stream.dto;

import com.reallive.streamservice.stream.entity.enums.MediaSourceType;

import java.time.LocalDateTime;
import java.util.UUID;

public record MediaOnlineCommand(
        UUID streamId,
        UUID mediaSourceId,
        MediaSourceType mediaSourceType,
        UUID mediaEventId,
        LocalDateTime occurredAt
) {}
