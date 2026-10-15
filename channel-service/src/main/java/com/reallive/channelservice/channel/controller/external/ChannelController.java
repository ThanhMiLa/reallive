package com.reallive.channelservice.channel.controller.external;

import com.reallive.channelservice.channel.dto.request.ChannelListRequest;
import com.reallive.channelservice.channel.dto.request.UpdateChannelRequest;
import com.reallive.channelservice.channel.dto.response.ChannelResponse;
import com.reallive.channelservice.channel.dto.response.ChannelListResponse;
import com.reallive.channelservice.channel.service.ChannelService;
import com.reallive.channelservice.common.dto.ApiResponse;
import com.reallive.channelservice.common.dto.PageResponse;
import com.reallive.channelservice.common.util.AuthenticationUserId;

import jakarta.validation.Valid;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController("externalChannelController")
@RequestMapping("/api/channels")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChannelController {

    ChannelService channelService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ChannelListResponse>>> getChannels(
            @Valid @ModelAttribute("channelListRequest") ChannelListRequest request) {
        PageResponse<ChannelListResponse> channels = channelService.getChannels(request);
        return ResponseEntity.ok(ApiResponse.<PageResponse<ChannelListResponse>>builder()
                .status(200)
                .code(1000)
                .message("Channels retrieved successfully")
                .result(channels)
                .timestamp(Instant.now().toString())
                .build());
    }

    @PatchMapping(value = "/me", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('CHANNEL_UPDATE')")
    public ResponseEntity<ApiResponse<ChannelResponse>> updateChannel(
            @AuthenticationPrincipal Jwt jwt, @Valid @ModelAttribute("updateChannelRequest") UpdateChannelRequest request) {
        ChannelResponse response = channelService.updateChannel(
                AuthenticationUserId.getAuthenticatedUserId(jwt), request);
        return ResponseEntity.ok(ApiResponse.<ChannelResponse>builder()
                .status(200)
                .code(1000)
                .message("Channel updated successfully")
                .result(response)
                .timestamp(Instant.now().toString())
                .build());
    }
}
