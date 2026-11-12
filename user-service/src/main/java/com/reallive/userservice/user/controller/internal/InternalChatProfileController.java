package com.reallive.userservice.user.controller.internal;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.user.service.ProfileService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/internal/api/users")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class InternalChatProfileController {
    ProfileService profiles;

    @GetMapping("/{userId}/chat-profile")
    public ApiResponse<Snapshot> profile(@PathVariable UUID userId) {
        var profile = profiles.getMyProfile(userId);
        return ApiResponse.<Snapshot>builder().status(200).code(1000).message("Chat profile retrieved")
                .result(new Snapshot(userId, profile.getDisplayName(), profile.getAvatarUrl()))
                .timestamp(Instant.now().toString()).build();
    }

    public record Snapshot(
            UUID userId,
            String displayName,
            String avatarUrl
    ) {}
}
