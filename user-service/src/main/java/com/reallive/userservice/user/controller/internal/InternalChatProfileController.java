package com.reallive.userservice.user.controller.internal;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.user.service.ProfileService;
import com.reallive.userservice.user.dto.response.ChatProfileSnapshot;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/internal/api/users")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class InternalChatProfileController {
    ProfileService profiles;

    @GetMapping("/{userId}/chat-profile")
    public ResponseEntity<ApiResponse<Snapshot>> profile(@PathVariable UUID userId) {
        var profile = profiles.getMyProfile(userId);
        return ResponseEntity.ok(ApiResponse.<Snapshot>builder().status(200).code(1000).message("Chat profile retrieved")
                .result(new Snapshot(userId, profile.getDisplayName(), profile.getAvatarUrl()))
                .timestamp(Instant.now().toString()).build());
    }

    @PostMapping("/chat-profiles")
    public ResponseEntity<ApiResponse<List<ChatProfileSnapshot>>> profiles(@Valid @RequestBody BatchRequest request) {
        return ResponseEntity.ok(ApiResponse.<List<ChatProfileSnapshot>>builder().status(200).code(1000).message("Chat profiles retrieved")
                .result(profiles.getChatProfiles(request.userIds())).timestamp(Instant.now().toString()).build());
    }

    public record BatchRequest(
            @NotEmpty
            @Size(max = 50)
            List<@NotNull UUID> userIds
    ) {}

    public record Snapshot(
            UUID userId,
            String displayName,
            String avatarUrl
    ) {}
}
