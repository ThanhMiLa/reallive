package com.reallive.channelservice.channel.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

public record LivestreamSummariesRequest(
        @NotNull @Size(max = 100) Set<@NotNull UUID> channelIds,
        @NotNull @Size(max = 100) Set<@NotNull UUID> categoryIds
) {}
