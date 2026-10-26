package com.reallive.streamservice.stream.dto;

import com.reallive.streamservice.stream.dto.response.CreateStreamSessionResponse;

public record SessionProvisioningResult(
        CreateStreamSessionResponse response,
        boolean created
) {}
