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
    public ResponseEntity<ApiResponse<ChatBanResponse.State>> ban(@PathVariable UUID channelId, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(response(bans.ban(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, userId)));
    }

    @PostMapping("/users/{userId}/unban")
    @PreAuthorize("hasAuthority('CHANNEL_CHAT_UNBAN')")
    public ResponseEntity<ApiResponse<ChatBanResponse.State>> unban(@PathVariable UUID channelId, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(response(bans.unban(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, userId)));
    }

    @GetMapping("/users/{userId}/ban")
    @PreAuthorize("hasAnyAuthority('CHANNEL_CHAT_BAN', 'CHANNEL_CHAT_UNBAN', 'CHANNEL_CHAT_BAN_VIEW')")
    public ResponseEntity<ApiResponse<ChatBanResponse.State>> state(@PathVariable UUID channelId, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(response(bans.state(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, userId)));
    }

    @GetMapping("/bans")
    @PreAuthorize("hasAuthority('CHANNEL_CHAT_BAN_VIEW')")
    public ResponseEntity<ApiResponse<PageResponse<ChatBanResponse.Item>>> list(@PathVariable UUID channelId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(response(bans.list(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, page, size)));
    }

    @GetMapping("/moderation-context")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ChatBanResponse.Context>> context(@PathVariable UUID channelId, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        return ResponseEntity.ok(response(bans.context(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId,
                has(authentication, "CHANNEL_CHAT_BAN"), has(authentication, "CHANNEL_CHAT_UNBAN"),
                has(authentication, "CHANNEL_CHAT_BAN_VIEW"))));
    }

    private boolean has(Authentication authentication, String permission) {
        return authentication.getAuthorities().stream().anyMatch(authority -> permission.equals(authority.getAuthority()));
    }

    private <T> ApiResponse<T> response(T result) {
        return ApiResponse.<T>builder().status(200).code(1000).message("Success").result(result).timestamp(Instant.now().toString()).build();
    }
}
