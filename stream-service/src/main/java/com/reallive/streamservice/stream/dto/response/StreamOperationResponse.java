package com.reallive.streamservice.stream.dto.response;

import com.reallive.streamservice.stream.entity.enums.MediaOperationStatus;
import com.reallive.streamservice.stream.entity.enums.MediaOperationType;

import java.util.UUID;

public record StreamOperationResponse(
        UUID operationId,
        MediaOperationType operationType,
        MediaOperationStatus status,
        UUID channelId,
        long credentialGeneration,
        UUID targetSessionId,
        String lastError
) {}
