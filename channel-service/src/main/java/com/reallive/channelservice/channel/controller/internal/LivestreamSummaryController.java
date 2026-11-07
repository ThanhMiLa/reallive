package com.reallive.channelservice.channel.controller.internal;

import com.reallive.channelservice.channel.dto.request.LivestreamSummariesRequest;
import com.reallive.channelservice.channel.dto.response.LivestreamSummariesResponse;
import com.reallive.channelservice.channel.service.LivestreamSummaryService;
import com.reallive.channelservice.common.dto.ApiResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/api/channels")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class LivestreamSummaryController {
    LivestreamSummaryService service;

    @PostMapping("/livestream-summaries")
    public ApiResponse<LivestreamSummariesResponse> summaries(@Valid @RequestBody LivestreamSummariesRequest request) {
        return ApiResponse.<LivestreamSummariesResponse>builder()
                .status(200)
                .code(1000)
                .message("Livestream summaries retrieved")
                .result(service.getSummaries(request))
                .timestamp(Instant.now().toString())
                .build();
    }
}
