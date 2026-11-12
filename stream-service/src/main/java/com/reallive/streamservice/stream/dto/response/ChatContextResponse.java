package com.reallive.streamservice.stream.dto.response;

import java.util.UUID;

public record ChatContextResponse(
        UUID sessionId,
        UUID channelId,
        String visibility,
        String status
) {}
