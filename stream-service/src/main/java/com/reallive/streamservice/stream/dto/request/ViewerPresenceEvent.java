package com.reallive.streamservice.stream.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;

public record ViewerPresenceEvent(
        @NotNull UUID eventId,
        @NotNull Type eventType,
        @NotNull UUID sessionId,
        @NotNull UUID readerId,
        @NotNull @Pattern(regexp = "hlsSession") String readerType,
        UUID userId,
        @NotNull Instant occurredAt
) {
    public enum Type { READ, UNREAD }
}
