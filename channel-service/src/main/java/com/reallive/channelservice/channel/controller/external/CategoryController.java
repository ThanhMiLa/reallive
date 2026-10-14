package com.reallive.channelservice.channel.controller.external;

import com.reallive.channelservice.channel.dto.response.CategoryResponse;
import com.reallive.channelservice.channel.service.CategoryService;
import com.reallive.channelservice.common.dto.ApiResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController("externalCategoryController")
@RequestMapping("/api/categories")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CategoryController {

    CategoryService categoryService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> getAllCategories() {
        List<CategoryResponse> categories = categoryService.getAllCategories();
        return ResponseEntity.ok(ApiResponse.<List<CategoryResponse>>builder()
                .status(200)
                .code(1000)
                .message("Categories retrieved successfully")
                .result(categories)
                .timestamp(Instant.now().toString())
                .build());
    }
}
