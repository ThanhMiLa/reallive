package com.reallive.userservice.user.controller;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.common.util.AuthenticationUserId;
import com.reallive.userservice.user.dto.response.CategoryPreferencesResponse;
import com.reallive.userservice.user.service.CategoryPreferenceService;
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
@RequestMapping("/api/users/me/category-preferences")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CategoryPreferenceController {

    CategoryPreferenceService categoryPreferenceService;

    @GetMapping
    public ResponseEntity<ApiResponse<CategoryPreferencesResponse>> getCategoryPreferences(
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = AuthenticationUserId.getAuthenticatedUserId(jwt);
        CategoryPreferencesResponse response = categoryPreferenceService.getCategoryPreferences(userId);
        return ResponseEntity.ok(ApiResponse.<CategoryPreferencesResponse>builder()
                .status(200)
                .code(1000)
                .message("User category preferences retrieved successfully")
                .result(response)
                .build());
    }
}
