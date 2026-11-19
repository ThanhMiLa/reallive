package com.reallive.streamservice.client.user;

import com.reallive.streamservice.client.StreamClientConfiguration;
import com.reallive.streamservice.common.dto.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "user-service", url = "${app.service.user}", configuration = StreamClientConfiguration.class)
public interface UserApiClient {
    @GetMapping("/internal/api/streamers/by-user/{userId}/streaming-eligibility")
    ApiResponse<StreamingEligibility> getEligibility(@PathVariable UUID userId);

    @PostMapping("/internal/api/users/chat-profiles")
    ApiResponse<List<ViewerProfile>> getViewerProfiles(@RequestBody ViewerProfileBatchRequest request);

    record ViewerProfileBatchRequest(
            List<UUID> userIds
    ) {}
}
