package com.reallive.channelservice.client.user;

import com.reallive.channelservice.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "user-service", contextId = "userCategoryPreferences", url = "${app.service.user}")
public interface UserClient {

    @GetMapping("/internal/api/users/{userId}/category-preferences")
    ResponseEntity<ApiResponse<UserCategoryPreferencesResponse>> getCategoryPreferences(@PathVariable("userId") UUID userId);
}