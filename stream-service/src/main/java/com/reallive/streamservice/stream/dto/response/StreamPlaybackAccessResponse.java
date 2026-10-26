package com.reallive.streamservice.stream.dto.response;

import java.time.Instant;
import java.util.UUID;

public record StreamPlaybackAccessResponse(
        UUID sessionId,
        String playbackUrl,
        String readToken,
        Instant readExpiresAt
) {
    @Override
    public String toString() {
        return "StreamPlaybackAccessResponse[sessionId=" + sessionId + ", credentials=REDACTED]";
    }
}
