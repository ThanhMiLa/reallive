package com.reallive.streamservice.stream.dto.response;

import java.util.UUID;

/** Credentials returned only by an explicit issue request, never saved by the backend. */
public record StreamKeyResponse(
        UUID channelId,
        String serverUrl,
        String streamKey
) {
    @Override
    public String toString() {
        return "StreamKeyResponse[channelId=" + channelId + ", credentials=REDACTED]";
    }
}
