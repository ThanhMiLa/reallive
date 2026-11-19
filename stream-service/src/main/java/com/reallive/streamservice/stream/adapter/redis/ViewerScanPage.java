package com.reallive.streamservice.stream.adapter.redis;

import java.util.List;
import java.util.UUID;

/** Represents one atomic cursor scan step over a stream session's active viewers set. */
public record ViewerScanPage(
        Status status,
        String nextCursor,
        List<UUID> viewerIds
) {

    public enum Status {
        ACTIVE,
        CLOSED,
        UNAVAILABLE
    }

    public static ViewerScanPage closed() {
        return new ViewerScanPage(Status.CLOSED, "0", List.of());
    }

    public static ViewerScanPage unavailable() {
        return new ViewerScanPage(Status.UNAVAILABLE, "0", List.of());
    }
}
