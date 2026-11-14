package com.reallive.streamservice.stream.controller.external;

import com.reallive.streamservice.common.dto.ApiResponse;
import com.reallive.streamservice.common.util.AuthenticationUserId;
import com.reallive.streamservice.stream.dto.request.SaveStreamSetupRequest;
import com.reallive.streamservice.stream.dto.response.StreamDetailsResponse;
import com.reallive.streamservice.stream.dto.response.StreamKeyResponse;
import com.reallive.streamservice.stream.dto.response.StreamMetadataResponse;
import com.reallive.streamservice.stream.dto.response.StreamPlaybackAccessResponse;
import com.reallive.streamservice.stream.dto.response.StreamSessionResponse;
import com.reallive.streamservice.stream.dto.response.StreamSetupResponse;
import com.reallive.streamservice.stream.service.StreamDetailsService;
import com.reallive.streamservice.stream.service.StreamPlaybackService;
import com.reallive.streamservice.stream.service.StreamSessionService;
import com.reallive.streamservice.stream.service.StreamSetupService;

import jakarta.validation.Valid;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/streams")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamSessionController {

    StreamSetupService streamSetupService;
    StreamSessionService streamSessionService;
    StreamPlaybackService streamPlaybackService;
    StreamDetailsService streamDetailsService;

    @GetMapping("/me/setup")
    @PreAuthorize("hasAuthority('STREAM_VIEW_OWN') and hasAuthority('STREAM_PUBLISH')")
    public ResponseEntity<ApiResponse<StreamSetupResponse>> getStreamSetup(
            @AuthenticationPrincipal Jwt principal) {
        StreamSetupResponse response =
                streamSetupService.getOrCreateSetup(
                        AuthenticationUserId.getAuthenticatedUserId(principal));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(buildApiResponse(response, 200, "Stream setup retrieved successfully"));
    }

    @PutMapping("/me/setup")
    @PreAuthorize("hasAuthority('STREAM_UPDATE') and hasAuthority('STREAM_PUBLISH')")
    public ResponseEntity<ApiResponse<StreamSetupResponse>> saveStreamSetup(
            @AuthenticationPrincipal Jwt principal,
            @Valid @RequestBody SaveStreamSetupRequest request) {
        StreamSetupResponse response =
                streamSetupService.saveSetup(
                        AuthenticationUserId.getAuthenticatedUserId(principal), request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(buildApiResponse(response, 200, "Stream setup saved successfully"));
    }

    @PostMapping("/me/stream-key")
    @PreAuthorize("hasAuthority('STREAM_PUBLISH')")
    public ResponseEntity<ApiResponse<StreamKeyResponse>> issueStreamKey(
            @AuthenticationPrincipal Jwt principal) {
        StreamKeyResponse response =
                streamSetupService.issueStreamKey(
                        AuthenticationUserId.getAuthenticatedUserId(principal));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(buildApiResponse(response, 200, "Stream key issued; copy it now"));
    }

    @GetMapping("/me/current")
    @PreAuthorize("hasAuthority('STREAM_VIEW_OWN')")
    public ResponseEntity<ApiResponse<StreamSessionResponse>> getCurrentStreamSession(
            @AuthenticationPrincipal Jwt principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        buildApiResponse(
                                streamSessionService.current(
                                        AuthenticationUserId.getAuthenticatedUserId(principal)),
                                200,
                                "Current stream session retrieved"));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<ApiResponse<StreamMetadataResponse>> getStreamMetadata(
            @PathVariable UUID sessionId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        buildApiResponse(
                                streamSessionService.metadata(sessionId),
                                200,
                                "Stream metadata retrieved"));
    }

    @GetMapping("/{sessionId}/details")
    public ResponseEntity<ApiResponse<StreamDetailsResponse>> getStreamDetails(
            @PathVariable UUID sessionId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        buildApiResponse(
                                streamDetailsService.details(sessionId),
                                200,
                                "Stream details retrieved"));
    }

    @PostMapping("/{sessionId}/playback-access")
    public ResponseEntity<ApiResponse<StreamPlaybackAccessResponse>> issuePlaybackAccess(
            @PathVariable UUID sessionId, @AuthenticationPrincipal Jwt principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        buildApiResponse(
                                streamPlaybackService.access(
                                        sessionId,
                                        AuthenticationUserId.getAuthenticatedUserId(principal)),
                                200,
                                "Playback access issued successfully"));
    }

    private <T> ApiResponse<T> buildApiResponse(T result, int status, String message) {
        return ApiResponse.<T>builder()
                .status(status)
                .code(1000)
                .message(message)
                .result(result)
                .timestamp(Instant.now().toString())
                .build();
    }
}
