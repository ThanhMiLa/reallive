package com.reallive.channelservice.channel.dto.response;

import java.util.UUID;

public record ChatAccessResponse(
        UUID channelId,
        UUID userId,
        boolean canView,
        boolean canRead,
        boolean canSend
) {}
