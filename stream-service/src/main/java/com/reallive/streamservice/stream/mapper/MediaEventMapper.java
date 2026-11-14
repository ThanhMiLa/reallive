package com.reallive.streamservice.stream.mapper;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.dto.MediaEventOutcome;
import com.reallive.streamservice.stream.dto.MediaIngestEventCommand;
import com.reallive.streamservice.stream.dto.request.MediaEventRequest;
import com.reallive.streamservice.stream.dto.response.MediaEventResponse;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class MediaEventMapper {

    private static final String INGEST_PREFIX = "ingest/";

    public UUID extractIngestKeyId(String path) {
        if (path == null || !path.startsWith(INGEST_PREFIX)) {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }
        try {
            return UUID.fromString(path.substring(INGEST_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }
    }

    public MediaIngestEventCommand toIngestCommand(MediaEventRequest request) {
        UUID ingestKeyId = extractIngestKeyId(request.path());
        MediaSourceType sourceType = MediaSourceType.fromValue(request.sourceType());
        if (sourceType == null || !sourceType.isPublisher()) {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }
        LocalDateTime occurredAt = LocalDateTime.ofInstant(request.occurredAt(), ZoneOffset.UTC);
        return new MediaIngestEventCommand(
                ingestKeyId, request.sourceId(), sourceType, request.eventId(), occurredAt);
    }

    public MediaEventResponse toResponse(
            UUID streamId, UUID eventId, MediaEventOutcome outcome, StreamSessionStatus status) {
        return new MediaEventResponse(streamId, eventId, outcome, status);
    }
}
