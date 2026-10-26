package com.reallive.userservice.streamer.dto.response;

import java.util.UUID;

public record StreamingEligibilityResponse(
        UUID userId,
        UUID streamerId
) {}
