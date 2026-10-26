package com.reallive.streamservice.stream.client.channel;

import com.reallive.streamservice.common.dto.ApiResponse;
import com.reallive.streamservice.stream.client.StreamClientConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.Set;
import java.util.UUID;

@FeignClient(name = "channel-service", url = "${app.service.channel}", configuration = StreamClientConfiguration.class)
public interface ChannelApiClient {
    @GetMapping("/internal/api/channels/by-streamer/{streamerId}/streaming-context")
    ApiResponse<StreamingChannelContext> getContext(@PathVariable UUID streamerId);

    @PostMapping("/internal/api/categories/validate")
    ApiResponse<Boolean> validateCategories(@RequestBody CategoryIds request);

    record CategoryIds(Set<UUID> categoryIds) { }
}
