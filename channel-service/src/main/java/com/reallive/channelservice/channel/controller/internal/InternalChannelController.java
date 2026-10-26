package com.reallive.channelservice.channel.controller.internal;

import com.reallive.channelservice.channel.dto.request.CreateChannelRequest;
import com.reallive.channelservice.channel.dto.response.StreamingChannelContextResponse;
import com.reallive.channelservice.channel.dto.request.UpdateChannelProfileRequest;
import com.reallive.channelservice.channel.service.ChannelService;
import com.reallive.channelservice.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/internal/api/channels")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class InternalChannelController {

    ChannelService channelService;

    @PostMapping
    public ResponseEntity<ApiResponse<Boolean>> createChannel(
            @Valid @RequestBody CreateChannelRequest request) {
        boolean success = channelService.createChannel(request);
        return ResponseEntity.ok(ApiResponse.<Boolean>builder()
                .status(200)
                .code(1000)
                .message("Channel created successfully")
                .result(success)
                .timestamp(Instant.now().toString())
                .build());
    }

    @PatchMapping(value = "/by-streamer/{streamerId}/avatar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Boolean>> updateChannelProfile(
            @PathVariable UUID streamerId, @Valid @RequestBody UpdateChannelProfileRequest request) {
        boolean success = channelService.updateChannelProfile(streamerId, request);
        return ResponseEntity.ok(ApiResponse.<Boolean>builder()
                .status(200)
                .code(1000)
                .message("Channel profile updated successfully")
                .result(success)
                .timestamp(Instant.now().toString())
                .build());
    }

    @GetMapping("/by-streamer/{streamerId}/streaming-context")
    public ResponseEntity<ApiResponse<StreamingChannelContextResponse>> getStreamingContext(@PathVariable UUID streamerId) {
        var result = channelService.getStreamingContext(streamerId);
        return ResponseEntity.ok(ApiResponse.<StreamingChannelContextResponse>builder()
                .status(200).code(1000).message("Streaming channel confirmed")
                .result(result).timestamp(Instant.now().toString()).build());
    }
}
