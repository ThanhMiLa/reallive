package com.reallive.channelservice.channel.controller.external;

import com.reallive.channelservice.channel.dto.response.ChatBanResponse;
import com.reallive.channelservice.channel.service.ChatBanService;
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
@RequestMapping("/api/channels/{channelId}/chat")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChannelChatBanController {
    ChatBanService bans;

    @PostMapping("/users/{userId}/ban")
    @PreAuthorize("hasAuthority('CHANNEL_CHAT_BAN')")
    public ResponseEntity<ApiResponse<ChatBanResponse.State>> banUserFromChat(@PathVariable UUID channelId, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(buildResponse(bans.banUserFromChat(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, userId)));
    }

    @PostMapping("/users/{userId}/unban")
    @PreAuthorize("hasAuthority('CHANNEL_CHAT_UNBAN')")
    public ResponseEntity<ApiResponse<ChatBanResponse.State>> unbanUserFromChat(@PathVariable UUID channelId, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(buildResponse(bans.unbanUserFromChat(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, userId)));
    }

    @GetMapping("/users/{userId}/ban")
    @PreAuthorize("hasAnyAuthority('CHANNEL_CHAT_BAN', 'CHANNEL_CHAT_UNBAN', 'CHANNEL_CHAT_BAN_VIEW')")
    public ResponseEntity<ApiResponse<ChatBanResponse.State>> getUserChatBanStatus(@PathVariable UUID channelId, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(buildResponse(bans.getUserChatBanStatus(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, userId)));
    }

    @GetMapping("/bans")
    @PreAuthorize("hasAuthority('CHANNEL_CHAT_BAN_VIEW')")
    public ResponseEntity<ApiResponse<PageResponse<ChatBanResponse.Item>>> getBannedUsers(@PathVariable UUID channelId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(buildResponse(bans.getBannedUsers(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, page, size)));
    }

    @GetMapping("/moderation-context")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ChatBanResponse.Context>> getChatModerationContext(@PathVariable UUID channelId, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        return ResponseEntity.ok(buildResponse(bans.getChatModerationContext(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId,
                hasPermission(authentication, "CHANNEL_CHAT_BAN"), hasPermission(authentication, "CHANNEL_CHAT_UNBAN"),
                hasPermission(authentication, "CHANNEL_CHAT_BAN_VIEW"))));
    }

    private boolean hasPermission(Authentication authentication, String permission) {
        return authentication.getAuthorities().stream().anyMatch(authority -> permission.equals(authority.getAuthority()));
    }

    private <T> ApiResponse<T> buildResponse(T result) {
        return ApiResponse.<T>builder().status(200).code(1000).message("Success").result(result).timestamp(Instant.now().toString()).build();
    }
}
