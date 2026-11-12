package com.reallive.streamservice.client.channel;

import java.util.UUID;

public record ChatAccessResponse(
        UUID channelId,
        UUID userId,
        Boolean canView,
        Boolean canRead,
        Boolean canSend
) {
    public record Request(
            UUID userId
    ) {}
}
