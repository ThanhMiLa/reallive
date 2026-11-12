package com.reallive.streamservice.client.thumbnail;

import java.util.List;

public record ThumbnailBatchRequest(
        List<ThumbnailJobPayload> jobs
) {
}
