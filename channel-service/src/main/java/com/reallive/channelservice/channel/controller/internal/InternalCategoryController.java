package com.reallive.channelservice.channel.controller.internal;

import com.reallive.channelservice.channel.dto.request.ValidateCategoriesRequest;
import com.reallive.channelservice.channel.service.CategoryService;
import com.reallive.channelservice.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/internal/api/categories")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class InternalCategoryController {

    CategoryService categoryService;

    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<Boolean>> validateCategories(
            @Valid @RequestBody ValidateCategoriesRequest request) {
        boolean valid = categoryService.validateCategories(request.getCategoryIds());
        return ResponseEntity.ok(ApiResponse.<Boolean>builder()
                .status(200)
                .code(1000)
                .message("Categories are valid and active")
                .result(valid)
                .timestamp(Instant.now().toString())
                .build());
    }
}
