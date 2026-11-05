package com.reallive.streamservice.stream.client.thumbnail;

import java.util.UUID;

public record RejectedThumbnailJob(
        UUID jobId,
        String reason
) {
}
