package com.reallive.channelservice.channel.client.user;

import com.reallive.channelservice.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.UUID;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "user-service", contextId = "chatModerationProfile", url = "${app.service.user}")
public interface UserChatProfileClient {
    @GetMapping("/internal/api/users/{userId}/chat-profile")
    ApiResponse<ChatProfile> profile(@PathVariable("userId") UUID userId);

    @PostMapping("/internal/api/users/chat-profiles")
    ApiResponse<List<ChatProfile>> profiles(@RequestBody BatchRequest request);

    record BatchRequest(
            List<UUID> userIds
    ) {}

    record ChatProfile(
            UUID userId,
            String displayName,
            String avatarUrl,
            String username
    ) {}
}
