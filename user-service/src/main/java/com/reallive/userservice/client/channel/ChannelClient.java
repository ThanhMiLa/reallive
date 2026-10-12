package com.reallive.userservice.client.channel;


import com.reallive.userservice.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;


@FeignClient(
        name = "channel-service",
        url = "${app.service.channel}")
public interface ChannelClient {
    @PostMapping(
            value = "/internal/api/channels",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<Boolean>> createChannel(@RequestBody CreateChannelRequest request);
}
