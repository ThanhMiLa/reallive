package com.reallive.streamservice.stream.dto.response;

import com.reallive.streamservice.stream.entity.enums.ChannelStreamConfigStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;

import java.util.UUID;

public record StreamSetupResponse(
        UUID channelId,
        String title,
        UUID categoryId,
        StreamVisibility visibility,
        ChannelStreamConfigStatus status,
        long generation,
        UUID operationId,
        String serverUrl,
        String streamKey,
        boolean publishReady,
        String blockedReason
) {
    @Override
    public String toString() {
        return "StreamSetupResponse[channelId=" + channelId + ", status=" + status
                + ", generation=" + generation + ", operationId=" + operationId
                + ", publishReady=" + publishReady + ", credentials=REDACTED]";
    }
}
