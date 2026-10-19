package com.reallive.userservice.user.controller.internal;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.user.dto.response.CategoryPreferencesResponse;
import com.reallive.userservice.user.service.CategoryPreferenceService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
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
public class InternalCategoryPreferenceController {

    CategoryPreferenceService categoryPreferenceService;

    @GetMapping("/{userId}/category-preferences")
    public ResponseEntity<ApiResponse<CategoryPreferencesResponse>> getCategoryPreferences(@PathVariable UUID userId) {
        CategoryPreferencesResponse response = categoryPreferenceService.getCategoryPreferences(userId);
        return ResponseEntity.ok(ApiResponse.<CategoryPreferencesResponse>builder()
                .status(200)
                .code(1000)
                .message("User category preferences retrieved successfully")
                .result(response)
                .timestamp(Instant.now().toString())
                .build());
    }
}
