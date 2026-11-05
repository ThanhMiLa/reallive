package com.reallive.streamservice.stream.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ThumbnailDispatchJob(
        UUID sessionId,
        UUID jobId,
        String playbackPath,
        LocalDateTime deadlineAt
) {
}
