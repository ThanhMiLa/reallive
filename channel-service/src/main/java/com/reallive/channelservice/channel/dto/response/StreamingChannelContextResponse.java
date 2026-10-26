package com.reallive.channelservice.channel.dto.response;

import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import java.util.UUID;

public record StreamingChannelContextResponse(
        UUID channelId,
        UUID streamerId,
        UUID ownerUserId,
        ChannelStatus status
) {}
