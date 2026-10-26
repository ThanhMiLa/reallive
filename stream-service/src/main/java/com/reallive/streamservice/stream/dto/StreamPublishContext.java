package com.reallive.streamservice.stream.dto;

import java.util.UUID;

public record StreamPublishContext(
        UUID ownerUserId,
        UUID streamerId,
        UUID channelId,
        UUID categoryId
) {}
