package com.reallive.streamservice.stream.controller.media;

import com.reallive.streamservice.common.dto.ApiResponse;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.dto.MediaIngestEventCommand;
import com.reallive.streamservice.stream.dto.request.MediaEventRequest;
import com.reallive.streamservice.stream.dto.response.MediaEventResponse;
import com.reallive.streamservice.stream.mapper.MediaEventMapper;
import com.reallive.streamservice.stream.service.StreamMediaLifecycleService;

import jakarta.validation.Valid;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Slf4j
@RestController
@RequestMapping("/internal/api/media-events")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MediaEventController {

    StreamMediaLifecycleService lifecycleService;
    MediaEventMapper mapper;

    @PostMapping
    public ResponseEntity<ApiResponse<MediaEventResponse>> handleEvent(
            @Valid @RequestBody MediaEventRequest request) {
        MediaIngestEventCommand command = mapper.toIngestCommand(request);
        MediaEventResponse response;
        if ("ONLINE".equalsIgnoreCase(request.eventType())) {
            response = lifecycleService.onOnline(command);
        } else if ("OFFLINE".equalsIgnoreCase(request.eventType())) {
            response = lifecycleService.onOffline(command);
        } else {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }

        log.info(
                "Processed media ingest event: eventId={}, ingestKeyId={}, streamId={}, type={},"
                        + " outcome={}, status={}",
                request.eventId(),
                command.ingestKeyId(),
                response.streamId(),
                request.eventType(),
                response.outcome(),
                response.status());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.<MediaEventResponse>builder()
                                .status(200)
                                .code(1000)
                                .message("Media event processed")
                                .result(response)
                                .timestamp(Instant.now().toString())
                                .build());
    }
}
