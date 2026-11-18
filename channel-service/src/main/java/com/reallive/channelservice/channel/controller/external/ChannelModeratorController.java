package com.reallive.channelservice.channel.controller.external;

import com.reallive.channelservice.channel.dto.response.ChannelModeratorResponse.Candidate;
import com.reallive.channelservice.channel.dto.response.ChannelModeratorResponse.Item;
import com.reallive.channelservice.channel.service.ChannelModeratorService;
import com.reallive.channelservice.common.dto.ApiResponse;
import com.reallive.channelservice.common.dto.PageResponse;
import com.reallive.channelservice.common.util.AuthenticationUserId;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/channels/{channelId}/moderators")
@PreAuthorize("hasAuthority('CHANNEL_MODERATOR_MANAGE')")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChannelModeratorController {

    ChannelModeratorService moderators;

    @GetMapping
    public ApiResponse<PageResponse<Item>> getChannelModerators(
            @PathVariable UUID channelId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {
        return buildSuccessResponse(moderators.list(
                AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, page, size));
    }

    @GetMapping("/candidate")
    public ApiResponse<PageResponse<Candidate>> searchChannelModeratorCandidates(
            @PathVariable UUID channelId,
            @RequestParam String displayName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {
        return buildSuccessResponse(moderators.searchCandidates(
                AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, displayName, page, size));
    }

    @PostMapping("/{userId}")
    public ResponseEntity<ApiResponse<AssignmentResponse>> assignChannelModerator(
            @PathVariable UUID channelId, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        moderators.assign(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<AssignmentResponse>builder()
                .status(HttpStatus.CREATED.value())
                .code(1000)
                .message("Channel moderator assigned")
                .result(new AssignmentResponse(channelId, userId))
                .timestamp(Instant.now().toString())
                .build());
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> removeChannelModerator(
            @PathVariable UUID channelId, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        moderators.remove(AuthenticationUserId.getAuthenticatedUserId(jwt), channelId, userId);
        return ResponseEntity.noContent().build();
    }

    private <T> ApiResponse<T> buildSuccessResponse(T result) {
        return ApiResponse.<T>builder().status(200).code(1000).message("Success")
                .result(result).timestamp(Instant.now().toString()).build();
    }

    public record AssignmentResponse(
            UUID channelId,
            UUID userId
    ) {}
}
