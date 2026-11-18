package com.reallive.channelservice.channel.client.user;

import com.reallive.channelservice.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "user-service", contextId = "channelModeratorUserStatus", url = "${app.service.user}")
public interface UserStatusClient {

    @GetMapping("/internal/api/users/{userId}/status")
    ApiResponse<StatusResponse> getStatus(@PathVariable("userId") UUID userId);

    record StatusResponse(
            UUID userId,
            Status status
    ) {}

    enum Status {
        ACTIVE,
        BANNED
    }
}
