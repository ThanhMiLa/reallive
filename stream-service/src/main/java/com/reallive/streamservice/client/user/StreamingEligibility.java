package com.reallive.streamservice.client.user;

import java.util.UUID;

public record StreamingEligibility(
        UUID userId,
        UUID streamerId
) {}
