package com.reallive.userservice.user.controller.external;

import com.reallive.userservice.common.dto.ApiResponse;
import com.reallive.userservice.common.util.AuthenticationUserId;
import com.reallive.userservice.user.dto.request.VerifyEmailOtpRequest;
import com.reallive.userservice.user.dto.response.EmailVerificationOtpResponse;
import com.reallive.userservice.user.dto.response.EmailVerificationResponse;
import com.reallive.userservice.user.service.EmailVerificationService;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users/me/email-verification")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class EmailVerificationController {

    EmailVerificationService emailVerificationService;

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<EmailVerificationResponse>> verifyOtp(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VerifyEmailOtpRequest request) {
        EmailVerificationResponse result = emailVerificationService.verifyEmailOtp(
                AuthenticationUserId.getAuthenticatedUserId(jwt), request);
        return ResponseEntity.ok(ApiResponse.<EmailVerificationResponse>builder()
                .status(200)
                .code(1000)
                .message("Email verified successfully")
                .result(result)
                .timestamp(Instant.now().toString())
                .build());
    }

    @PostMapping("/otp")
    public ResponseEntity<ApiResponse<EmailVerificationOtpResponse>> requestOtp(@AuthenticationPrincipal Jwt jwt) {
        EmailVerificationOtpResponse result = emailVerificationService.requestEmailVerificationOtp(
                AuthenticationUserId.getAuthenticatedUserId(jwt));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.<EmailVerificationOtpResponse>builder()
                .status(202)
                .code(1000)
                .message("Email verification request accepted")
                .result(result)
                .timestamp(Instant.now().toString())
                .build());
    }
}
