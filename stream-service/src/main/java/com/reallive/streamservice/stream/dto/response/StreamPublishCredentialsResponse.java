package com.reallive.streamservice.stream.dto.response;

import java.time.Instant;
import java.util.UUID;

public record StreamPublishCredentialsResponse(
        UUID sessionId,
        String serverUrl,
        String streamKey,
        Instant publishExpiresAt
) {
    @Override
    public String toString() {
        return "StreamPublishCredentialsResponse[sessionId=" + sessionId + ", credentials=REDACTED]";
    }
}
