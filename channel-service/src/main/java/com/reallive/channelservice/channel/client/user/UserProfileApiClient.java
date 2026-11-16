package com.reallive.channelservice.channel.client.user;

import com.reallive.channelservice.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

@FeignClient(name = "user-service", contextId = "userProfile", url = "${app.service.user}")
public interface UserProfileApiClient {
    @PatchMapping(value = "/internal/api/users/{userId}/channel-profile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<UserProfileResponse> update(@PathVariable("userId") UUID userId,
            @RequestPart(value = "displayName", required = false) String displayName,
            @RequestPart(value = "avatar", required = false) MultipartFile avatar);
}
