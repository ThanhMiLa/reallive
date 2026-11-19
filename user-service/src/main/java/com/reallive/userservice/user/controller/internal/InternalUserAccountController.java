package com.reallive.userservice.user.controller.internal;

import com.reallive.userservice.auth.dto.response.UserIdentityResponse;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.service.UserStatusService;
import com.reallive.userservice.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/api/users")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class InternalUserAccountController {

    UserStatusService userStatusService;

    @GetMapping("/{userId}/status")
    public ApiResponse<StatusResponse> getStatus(@PathVariable UUID userId) {
        return ApiResponse.<StatusResponse>builder()
                .status(200)
                .code(1000)
                .message("User status retrieved")
                .result(new StatusResponse(userId, userStatusService.getStatus(userId)))
                .timestamp(Instant.now().toString())
                .build();
    }

    @PostMapping("/identities")
    public ApiResponse<UserIdentityResponse.Page> searchIdentities(@Valid @RequestBody IdentitySearchRequest request) {
        return ApiResponse.<UserIdentityResponse.Page>builder()
                .status(200)
                .code(1000)
                .message("User identities retrieved")
                .result(userStatusService.searchIdentities(
                        request.displayName(), request.followerIds(), request.page(), request.size()))
                .timestamp(Instant.now().toString())
                .build();
    }

    public record IdentitySearchRequest(
            @NotNull String displayName,
            @NotNull List<@NotNull UUID> followerIds,
            @NotNull Integer page,
            @NotNull Integer size
    ) {}

    public record StatusResponse(
            UUID userId,
            UserStatus status
    ) {}
}
