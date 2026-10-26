package com.reallive.streamservice.stream.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;

public record MediaEventRequest(
        @NotNull
        UUID eventId,

        @NotNull
        @Pattern(regexp = "ONLINE|OFFLINE")
        String eventType,

        @NotNull
        @Pattern(regexp = "live/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        String path,

        @NotNull
        UUID sourceId,

        @NotNull
        @Pattern(regexp = "rtmpConn|rtmpsConn")
        String sourceType,

        @NotNull
        Instant occurredAt
) {}
