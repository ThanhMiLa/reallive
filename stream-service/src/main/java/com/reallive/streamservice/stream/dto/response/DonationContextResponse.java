package com.reallive.streamservice.stream.dto.response;

import java.util.UUID;

public record DonationContextResponse(
        UUID sessionId,
        UUID channelId,
        UUID streamerId,
        UUID ownerUserId,
        String visibility,
        String status
) {}
