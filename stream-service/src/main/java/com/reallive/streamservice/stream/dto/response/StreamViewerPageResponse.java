package com.reallive.streamservice.stream.dto.response;

import java.util.List;
import java.util.UUID;

public record StreamViewerPageResponse(
        List<ViewerItem> items,
        String nextCursor,
        boolean hasMore
) {

    public record ViewerItem(
            UUID userId,
            String displayName,
            String avatarUrl
    ) {}
}
