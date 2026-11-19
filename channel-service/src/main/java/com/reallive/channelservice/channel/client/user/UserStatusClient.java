package com.reallive.channelservice.channel.client.user;

import com.reallive.channelservice.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

@FeignClient(name = "user-service", contextId = "channelModeratorUserStatus", url = "${app.service.user}")
public interface UserStatusClient {

    @GetMapping("/internal/api/users/{userId}/status")
    ApiResponse<StatusResponse> getStatus(@PathVariable("userId") UUID userId);

    @PostMapping("/internal/api/users/identities")
    ApiResponse<IdentityPage> searchIdentities(@RequestBody IdentitySearchRequest request);

    record IdentitySearchRequest(
            String displayName,
            List<UUID> followerIds,
            int page,
            int size
    ) {}

    record IdentityPage(
            List<UserIdentity> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}

    record UserIdentity(
            UUID userId,
            String displayName,
            String avatarUrl,
            Status status
    ) {}

    record StatusResponse(
            UUID userId,
            Status status
    ) {}

    enum Status {
        ACTIVE,
        BANNED
    }
}
