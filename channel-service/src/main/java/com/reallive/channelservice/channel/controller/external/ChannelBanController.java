package com.reallive.channelservice.channel.controller.external;

import com.reallive.channelservice.channel.dto.request.SetChannelUserBanRequest;
import com.reallive.channelservice.channel.dto.response.ChannelBanResponse;
import com.reallive.channelservice.channel.service.ChannelBanService;
import com.reallive.channelservice.common.dto.ApiResponse;
import com.reallive.channelservice.common.dto.PageResponse;
import com.reallive.channelservice.common.util.AuthenticationUserId;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/channels/{channelId}")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChannelBanController {
    ChannelBanService bans;

    @PostMapping("/users/{userId}/ban")
    @PreAuthorize("@channelAuthorization.canModerate(authentication, #channelId)")
    public ResponseEntity<ApiResponse<ChannelBanResponse.State>> banUserFromChannel(
            @PathVariable UUID channelId,
            @PathVariable UUID userId,
            @Valid @RequestBody SetChannelUserBanRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(buildResponse(bans.banUserFromChannel(
                AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, userId, request.banType())));
    }

    @PostMapping("/users/{userId}/unban")
    @PreAuthorize("@channelAuthorization.canModerate(authentication, #channelId)")
    public ResponseEntity<ApiResponse<ChannelBanResponse.State>> unbanUserFromChannel(
            @PathVariable UUID channelId, @PathVariable UUID userId) {
        return ResponseEntity.ok(buildResponse(bans.unbanUserFromChannel(channelId, userId)));
    }

    @GetMapping("/users/{userId}/ban")
    @PreAuthorize("@channelAuthorization.canModerate(authentication, #channelId)")
    public ResponseEntity<ApiResponse<ChannelBanResponse.State>> getUserBanStatus(
            @PathVariable UUID channelId, @PathVariable UUID userId) {
        return ResponseEntity.ok(buildResponse(bans.getUserBanStatus(channelId, userId)));
    }

    @GetMapping("/bans")
    @PreAuthorize("@channelAuthorization.canModerate(authentication, #channelId)")
    public ResponseEntity<ApiResponse<PageResponse<ChannelBanResponse.Item>>> getBannedUsers(
            @PathVariable UUID channelId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(buildResponse(bans.getBannedUsers(channelId, page, size)));
    }

    @GetMapping("/moderation-context")
    public ResponseEntity<ApiResponse<ChannelBanResponse.Context>> getChannelModerationContext(
            @PathVariable UUID channelId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(buildResponse(bans.getChannelModerationContext(
                AuthenticationUserId.getAuthenticatedUserId(jwt), channelId)));
    }

    private <T> ApiResponse<T> buildResponse(T result) {
        return ApiResponse.<T>builder().status(200).code(1000).message("Success").result(result).timestamp(Instant.now().toString()).build();
    }
}
