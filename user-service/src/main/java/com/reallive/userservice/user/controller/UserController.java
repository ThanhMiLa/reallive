package com.reallive.userservice.user.controller;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.dto.response.UserProfileResponse;
import com.reallive.userservice.user.service.UserService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserController {
    UserService userService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getMyProfile(@AuthenticationPrincipal Jwt jwt) {
        UserProfileResponse profile = userService.getMyProfile(getAuthenticatedUserId(jwt));
        return ResponseEntity.ok(ApiResponse.<UserProfileResponse>builder()
                .status(200)
                .code(1000)
                .message("User profile retrieved successfully")
                .result(profile)
                .build());
    }

    private UUID getAuthenticatedUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
