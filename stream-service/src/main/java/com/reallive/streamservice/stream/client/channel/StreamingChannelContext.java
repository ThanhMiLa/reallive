package com.reallive.streamservice.stream.client.channel;

import java.util.UUID;

public record StreamingChannelContext(
        UUID channelId,
        UUID streamerId,
        UUID ownerUserId,
        String status
) {}
