package com.reallive.streamservice.stream.adapter.media;

import java.time.Instant;

public record MediaToken(
        String value,
        Instant expiresAt
) {
    @Override
    public String toString() {
        return "MediaToken[expiresAt=" + expiresAt + ", value=REDACTED]";
    }
}
