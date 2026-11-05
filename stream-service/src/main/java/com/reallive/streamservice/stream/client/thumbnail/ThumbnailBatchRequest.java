package com.reallive.streamservice.stream.client.thumbnail;

import java.util.List;

public record ThumbnailBatchRequest(
        List<ThumbnailJobPayload> jobs
) {
}
