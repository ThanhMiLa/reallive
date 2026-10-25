package com.reallive.streamservice.stream.dto;

import java.util.UUID;

public record CreateSessionCommand(
        UUID channelId,
        UUID streamerId,
        UUID ownerUserId,
        UUID categoryId,
        String title,
        UUID creationRequestId
) {}
