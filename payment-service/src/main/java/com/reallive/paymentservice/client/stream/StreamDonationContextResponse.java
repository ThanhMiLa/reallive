package com.reallive.paymentservice.client.stream;

import java.util.UUID;

public record StreamDonationContextResponse(
        UUID sessionId,
        UUID channelId,
        UUID streamerId,
        UUID ownerUserId,
        String visibility,
        String status
) {}
