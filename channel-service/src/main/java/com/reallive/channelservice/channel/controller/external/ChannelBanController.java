package com.reallive.channelservice.channel.controller.external;

import com.reallive.channelservice.channel.dto.response.ChannelBanResponse;
import com.reallive.channelservice.channel.dto.request.SetChannelUserBanRequest;
import jakarta.validation.Valid;
import com.reallive.channelservice.channel.service.ChannelBanService;
import com.reallive.channelservice.common.dto.ApiResponse;
import com.reallive.channelservice.common.dto.PageResponse;
import com.reallive.channelservice.common.util.AuthenticationUserId;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/channels/{channelId}")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChannelBanController {
    ChannelBanService bans;

    @PostMapping("/users/{userId}/ban")
    @PreAuthorize("hasAnyAuthority('CHANNEL_CHAT_BAN', 'CHANNEL_LIVE_VIEW_BAN', 'CHANNEL_FULL_BAN')")
    public ResponseEntity<ApiResponse<ChannelBanResponse.State>> banUserFromChannel(@PathVariable UUID channelId, @PathVariable UUID userId, @Valid @RequestBody SetChannelUserBanRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(buildResponse(bans.banUserFromChannel(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, userId, request.banType())));
    }

    @PostMapping("/users/{userId}/unban")
    @PreAuthorize("hasAnyAuthority('CHANNEL_CHAT_UNBAN', 'CHANNEL_LIVE_VIEW_UNBAN', 'CHANNEL_FULL_UNBAN')")
    public ResponseEntity<ApiResponse<ChannelBanResponse.State>> unbanUserFromChannel(@PathVariable UUID channelId, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(buildResponse(bans.unbanUserFromChannel(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, userId)));
    }

    @GetMapping("/users/{userId}/ban")
    @PreAuthorize("hasAnyAuthority('CHANNEL_CHAT_BAN', 'CHANNEL_CHAT_UNBAN', 'CHANNEL_LIVE_VIEW_BAN', 'CHANNEL_LIVE_VIEW_UNBAN', 'CHANNEL_FULL_BAN', 'CHANNEL_FULL_UNBAN', 'CHANNEL_BAN_VIEW')")
    public ResponseEntity<ApiResponse<ChannelBanResponse.State>> getUserBanStatus(@PathVariable UUID channelId, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(buildResponse(bans.getUserBanStatus(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, userId)));
    }

    @GetMapping("/bans")
    @PreAuthorize("hasAuthority('CHANNEL_BAN_VIEW')")
    public ResponseEntity<ApiResponse<PageResponse<ChannelBanResponse.Item>>> getBannedUsers(@PathVariable UUID channelId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(buildResponse(bans.getBannedUsers(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, page, size)));
    }

    @GetMapping("/moderation-context")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ChannelBanResponse.Context>> getChannelModerationContext(@PathVariable UUID channelId, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        return ResponseEntity.ok(buildResponse(bans.getChannelModerationContext(
                AuthenticationUserId.getAuthenticatedUserId(jwt), channelId)));
    }

    private <T> ApiResponse<T> buildResponse(T result) {
        return ApiResponse.<T>builder().status(200).code(1000).message("Success").result(result).timestamp(Instant.now().toString()).build();
    }
}
