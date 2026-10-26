package com.reallive.streamservice.stream.controller.external;

import com.reallive.streamservice.common.dto.ApiResponse;
import com.reallive.streamservice.common.util.AuthenticationUserId;
import com.reallive.streamservice.stream.dto.request.CreateStreamSessionRequest;
import com.reallive.streamservice.stream.dto.request.UpdateStreamSessionRequest;
import com.reallive.streamservice.stream.dto.response.CreateStreamSessionResponse;
import com.reallive.streamservice.stream.dto.response.StreamPublishCredentialsResponse;
import com.reallive.streamservice.stream.dto.response.StreamSessionResponse;
import com.reallive.streamservice.stream.service.StreamSessionService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    StreamSessionService service;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateStreamSessionResponse>> create(
            @AuthenticationPrincipal Jwt principal, @Valid @RequestBody CreateStreamSessionRequest request) {
        var result = service.create(AuthenticationUserId.getAuthenticatedUserId(principal), request);
        int status = result.created() ? 201 : 200;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(response(result.response(), status, "Stream session provisioned successfully"));
    }

    @GetMapping("/me/current")
    public ResponseEntity<ApiResponse<StreamSessionResponse>> current(@AuthenticationPrincipal Jwt principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response(
                service.current(AuthenticationUserId.getAuthenticatedUserId(principal)), 200, "Current stream session retrieved"));
    }

    @PatchMapping("/{sessionId}")
    public ResponseEntity<ApiResponse<StreamSessionResponse>> update(
            @PathVariable UUID sessionId, @AuthenticationPrincipal Jwt principal,
            @Valid @RequestBody UpdateStreamSessionRequest request) {
        return ResponseEntity.ok(response(service.update(sessionId,
                AuthenticationUserId.getAuthenticatedUserId(principal), request), 200, "Stream session updated successfully"));
    }

    @PostMapping("/{sessionId}/publish-credentials")
    public ResponseEntity<ApiResponse<StreamPublishCredentialsResponse>> publishCredentials(
            @PathVariable UUID sessionId, @AuthenticationPrincipal Jwt principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response(service.publishCredentials(
                sessionId, AuthenticationUserId.getAuthenticatedUserId(principal)), 200, "Publish credentials issued successfully"));
    }

    private <T> ApiResponse<T> response(T result, int status, String message) {
        return ApiResponse.<T>builder().status(status).code(1000).message(message).result(result)
                .timestamp(Instant.now().toString()).build();
    }
}
