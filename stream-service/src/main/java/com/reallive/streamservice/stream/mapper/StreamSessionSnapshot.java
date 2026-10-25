package com.reallive.streamservice.stream.mapper;

import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;

import java.time.LocalDateTime;
import java.util.UUID;

public record StreamSessionSnapshot(
        UUID id,
        UUID channelId,
        UUID streamerId,
        UUID ownerUserId,
        UUID categoryId,
        String title,
        StreamVisibility visibility,
        StreamSessionStatus status,
        String playbackPath,
        UUID creationRequestId,
        MediaPathState mediaPathState,
        UUID mediaSourceId,
        MediaSourceType mediaSourceType,
        LocalDateTime disconnectedAt,
        LocalDateTime reconnectDeadlineAt,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static StreamSessionSnapshot fromEntity(StreamSessionEntity entity) {
        if (entity == null) {
            return null;
        }
        return new StreamSessionSnapshot(
                entity.getId(),
                entity.getChannelId(),
                entity.getStreamerId(),
                entity.getOwnerUserId(),
                entity.getCategoryId(),
                entity.getTitle(),
                entity.getVisibility(),
                entity.getStatus(),
                entity.getPlaybackPath(),
                entity.getCreationRequestId(),
                entity.getMediaPathState(),
                entity.getMediaSourceId(),
                entity.getMediaSourceType(),
                entity.getDisconnectedAt(),
                entity.getReconnectDeadlineAt(),
                entity.getStartedAt(),
                entity.getEndedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
