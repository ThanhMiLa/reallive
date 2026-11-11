package com.reallive.streamservice.stream.dto;

import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import java.util.UUID;

/**
 * Captures current database status without loading a managed stream session entity.
 */
public record StreamSessionStatusSnapshot(
        UUID id,
        StreamSessionStatus status
) {}
