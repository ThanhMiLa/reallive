package com.reallive.streamservice.stream.mapper;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.dto.MediaEventOutcome;
import com.reallive.streamservice.stream.dto.MediaOfflineCommand;
import com.reallive.streamservice.stream.dto.MediaOnlineCommand;
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

    private static final String PATH_PREFIX = "live/";

    public UUID extractStreamId(String path) {
        if (path == null || !path.startsWith(PATH_PREFIX)) {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }
        try {
            return UUID.fromString(path.substring(PATH_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }
    }

    public MediaOnlineCommand toOnlineCommand(MediaEventRequest request) {
        UUID streamId = extractStreamId(request.path());
        MediaSourceType sourceType = MediaSourceType.fromValue(request.sourceType());
        LocalDateTime occurredAt = LocalDateTime.ofInstant(request.occurredAt(), ZoneOffset.UTC);
        return new MediaOnlineCommand(streamId, request.sourceId(), sourceType, request.eventId(), occurredAt);
    }

    public MediaOfflineCommand toOfflineCommand(MediaEventRequest request) {
        UUID streamId = extractStreamId(request.path());
        MediaSourceType sourceType = MediaSourceType.fromValue(request.sourceType());
        LocalDateTime occurredAt = LocalDateTime.ofInstant(request.occurredAt(), ZoneOffset.UTC);
        return new MediaOfflineCommand(streamId, request.sourceId(), sourceType, request.eventId(), occurredAt);
    }

    public MediaEventResponse toResponse(UUID streamId, UUID eventId, MediaEventOutcome outcome, StreamSessionStatus status) {
        return new MediaEventResponse(streamId, eventId, outcome, status);
    }
}
