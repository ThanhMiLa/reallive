package com.reallive.streamservice.client.channel;

import java.util.UUID;

public record StreamingChannelContext(
        UUID channelId,
        UUID streamerId,
        UUID ownerUserId,
        String status
) {}
