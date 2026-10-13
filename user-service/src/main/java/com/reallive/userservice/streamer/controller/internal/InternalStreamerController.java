package com.reallive.userservice.streamer.controller.internal;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.streamer.dto.response.ActiveStreamerResponse;
import com.reallive.userservice.streamer.service.StreamerService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/internal/api/streamers")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class InternalStreamerController {

    StreamerService streamerService;

    @GetMapping("/by-user/{userId}/active")
    public ResponseEntity<ApiResponse<ActiveStreamerResponse>> getActiveStreamerIdByUserId(@PathVariable("userId") UUID userId) {
        UUID streamerId = streamerService.getActiveStreamerIdByUserId(userId);
        ActiveStreamerResponse response = ActiveStreamerResponse.builder()
                .streamerId(streamerId)
                .build();
        return ResponseEntity.ok(ApiResponse.<ActiveStreamerResponse>builder()
                .status(200)
                .code(1000)
                .message("Active streamer found successfully")
                .result(response)
                .timestamp(Instant.now().toString())
                .build());
    }
}
