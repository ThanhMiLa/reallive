package com.reallive.channelservice.channel.client.streamer;

import com.reallive.channelservice.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.UUID;

@FeignClient(
        name = "user-service",
        url = "${app.service.user}")
public interface StreamerClient {
    @GetMapping(
            value = "/internal/api/streamers/by-user/{userId}/active",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<ActiveStreamerResponse>> getActiveStreamerIdByUserId(@PathVariable("userId") UUID userId);
}
