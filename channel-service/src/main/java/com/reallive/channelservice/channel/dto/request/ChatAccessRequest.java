package com.reallive.channelservice.channel.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ChatAccessRequest(
        @NotNull UUID userId
) {}
