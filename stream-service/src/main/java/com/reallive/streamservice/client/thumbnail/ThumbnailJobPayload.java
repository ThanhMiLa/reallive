package com.reallive.streamservice.client.thumbnail;

import java.util.UUID;

public record ThumbnailJobPayload(
        UUID jobId,
        UUID sessionId,
        String playbackUrl,
        String readToken,
        String readExpiresAt,
        String jobDeadlineAt
) {
    @Override
    public String toString() {
        return "ThumbnailJobPayload[jobId=" + jobId + ", sessionId=" + sessionId + ", readToken=REDACTED]";
    }
}
