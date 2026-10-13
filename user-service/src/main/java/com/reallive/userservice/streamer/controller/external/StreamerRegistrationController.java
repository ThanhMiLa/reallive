package com.reallive.userservice.streamer.controller.external;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.common.util.AuthenticationUserId;
import com.reallive.userservice.streamer.dto.response.BecomeStreamerResponse;
import com.reallive.userservice.streamer.service.StreamerRegistrationService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamerRegistrationController {

    StreamerRegistrationService registrationService;

    @PostMapping("/api/users/me/become-streamer")
    public ResponseEntity<ApiResponse<BecomeStreamerResponse>> becomeStreamer(@AuthenticationPrincipal Jwt jwt) {
        BecomeStreamerResponse result = registrationService.becomeStreamer(AuthenticationUserId.getAuthenticatedUserId(jwt));
        return ResponseEntity.ok(ApiResponse.<BecomeStreamerResponse>builder()
                .status(200)
                .code(1000)
                .message("Streamer registered successfully")
                .result(result)
                .timestamp(Instant.now().toString())
                .build());
    }
}
