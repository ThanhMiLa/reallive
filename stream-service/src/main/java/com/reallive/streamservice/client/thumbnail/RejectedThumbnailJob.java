package com.reallive.streamservice.client.thumbnail;

import java.util.UUID;

public record RejectedThumbnailJob(
        UUID jobId,
        String reason
) {
}
