package com.reallive.streamservice.stream.mapper;

import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.dto.response.StreamSessionResponse;
import com.reallive.streamservice.stream.dto.response.StreamMetadataResponse;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

@Component
public class StreamSessionMapper {

    public StreamSessionSnapshot toSnapshot(StreamSessionEntity entity) {
        return StreamSessionSnapshot.fromEntity(entity);
    }

    public StreamSessionResponse toOwnerResponse(StreamSessionEntity entity, Instant serverTime) {
        boolean ready = entity.getMediaPathState() == MediaPathState.OPEN && entity.getStatus() != StreamSessionStatus.ENDED
                && (entity.getStatus() != StreamSessionStatus.RECONNECTING || (entity.getReconnectDeadlineAt() != null
                && serverTime.isBefore(utc(entity.getReconnectDeadlineAt()))));
        return toOwnerResponse(entity, serverTime, ready);
    }

    public StreamSessionResponse toOwnerResponse(StreamSessionEntity entity, Instant serverTime, boolean publishReady) {
        return new StreamSessionResponse(entity.getId(), entity.getChannelId(), entity.getTitle(), entity.getCategoryId(),
                entity.getVisibility(), entity.getStatus(), utc(entity.getStartedAt()), utc(entity.getEndedAt()),
                utc(entity.getReconnectDeadlineAt()), serverTime,
                publishReady);
    }

    public StreamMetadataResponse toMetadataResponse(StreamSessionEntity entity, Instant serverTime) {
        return new StreamMetadataResponse(entity.getId(), entity.getChannelId(), entity.getTitle(), entity.getCategoryId(),
                entity.getVisibility(), entity.getStatus(), utc(entity.getStartedAt()), utc(entity.getEndedAt()),
                utc(entity.getReconnectDeadlineAt()), serverTime);
    }

    private Instant utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
