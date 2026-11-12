package com.reallive.streamservice.client.thumbnail;

import java.util.List;
import java.util.UUID;

public record ThumbnailBatchResponse(
        List<UUID> acceptedJobIds,
        List<RejectedThumbnailJob> rejectedJobs
) {
}
