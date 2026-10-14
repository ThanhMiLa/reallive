package com.reallive.userservice.client.channel;


import com.reallive.userservice.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;


@FeignClient(
        name = "channel-service",
        url = "${app.service.channel}")
public interface ChannelClient {
    @PostMapping(
            value = "/internal/api/channels",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<Boolean>> createChannel(@RequestBody CreateChannelRequest request);

    @PatchMapping(
            value = "/internal/api/channels/by-streamer/{streamerId}/avatar",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<Boolean>> updateChannelProfile(@PathVariable("streamerId") UUID streamerId,
                                                              @RequestBody UpdateChannelProfileRequest request);

    @PostMapping(
            value = "/internal/api/categories/validate",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<Boolean>> validateCategories(@RequestBody ValidateCategoriesRequest request);
}
