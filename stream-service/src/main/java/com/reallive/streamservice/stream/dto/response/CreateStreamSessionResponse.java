package com.reallive.streamservice.stream.dto.response;

public record CreateStreamSessionResponse(
        StreamSessionResponse session,
        StreamPublishCredentialsResponse publishCredentials
) {}
