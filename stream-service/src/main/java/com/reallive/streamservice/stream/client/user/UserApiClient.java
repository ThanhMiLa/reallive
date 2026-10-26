package com.reallive.streamservice.stream.client.user;

import com.reallive.streamservice.common.dto.ApiResponse;
import com.reallive.streamservice.stream.client.StreamClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.UUID;

@FeignClient(name = "user-service", url = "${app.service.user}", configuration = StreamClientConfiguration.class)
public interface UserApiClient {
    @GetMapping("/internal/api/streamers/by-user/{userId}/streaming-eligibility")
    ApiResponse<StreamingEligibility> getEligibility(@PathVariable UUID userId);
}
