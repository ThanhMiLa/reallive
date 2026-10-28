package com.reallive.streamservice.stream.controller.media;

import com.reallive.streamservice.common.dto.ApiResponse;
import com.reallive.streamservice.stream.dto.request.MediaEventRequest;
import com.reallive.streamservice.stream.dto.response.MediaEventResponse;
import com.reallive.streamservice.stream.mapper.MediaEventMapper;
import com.reallive.streamservice.stream.service.StreamMediaLifecycleService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Slf4j
@RestController
@RequestMapping("/api/media/events")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MediaEventController {

    StreamMediaLifecycleService lifecycleService;
    MediaEventMapper mapper;

    @PostMapping
    public ResponseEntity<ApiResponse<MediaEventResponse>> handleEvent(@Valid @RequestBody MediaEventRequest request) {
        var result = "ONLINE".equalsIgnoreCase(request.eventType())
                ? lifecycleService.onOnline(mapper.toOnlineCommand(request))
                : lifecycleService.onOffline(mapper.toOfflineCommand(request));

        log.info("Processed media event: eventId={}, streamId={}, type={}, outcome={}, status={}",
                request.eventId(), result.session().getId(), request.eventType(), result.outcome(), result.session().getStatus());

        var response = mapper.toResponse(
                result.session().getId(),
                request.eventId(),
                result.outcome(),
                result.session().getStatus()
        );

        return ResponseEntity.ok(ApiResponse.<MediaEventResponse>builder()
                .status(200)
                .code(1000)
                .message("Media event processed")
                .result(response)
                .timestamp(Instant.now().toString())
                .build());
    }
}
