package com.reallive.userservice.auth.controller.internal;

import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.service.UserStatusService;
import com.reallive.userservice.common.dto.ApiResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/internal/api/users")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class InternalUserStatusController {

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

    public record StatusResponse(
            UUID userId,
            UserStatus status
    ) {}
}
