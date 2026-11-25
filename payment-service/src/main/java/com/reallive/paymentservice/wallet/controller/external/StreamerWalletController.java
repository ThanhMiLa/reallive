package com.reallive.paymentservice.wallet.controller.external;

import com.reallive.paymentservice.common.dto.ApiResponse;
import com.reallive.paymentservice.common.util.AuthenticationUserId;
import com.reallive.paymentservice.wallet.dto.response.StreamerWalletResponse;
import com.reallive.paymentservice.wallet.service.StreamerWalletService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/streamer-wallets")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamerWalletController {

    StreamerWalletService streamerWalletService;

    @PostMapping("/me")
    @PreAuthorize("hasRole('STREAMER')")
    public ApiResponse<StreamerWalletResponse> createWallet(@AuthenticationPrincipal Jwt jwt) {
        return response(streamerWalletService.createWallet(AuthenticationUserId.getAuthenticatedUserId(jwt)),
                "Streamer wallet ready");
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('STREAMER')")
    public ApiResponse<StreamerWalletResponse> getBalance(@AuthenticationPrincipal Jwt jwt) {
        return response(streamerWalletService.getBalance(AuthenticationUserId.getAuthenticatedUserId(jwt)),
                "Streamer wallet balance retrieved successfully");
    }

    private static ApiResponse<StreamerWalletResponse> response(StreamerWalletResponse wallet, String message) {
        return ApiResponse.<StreamerWalletResponse>builder()
                .status(200)
                .code(0)
                .message(message)
                .result(wallet)
                .timestamp(Instant.now().toString())
                .build();
    }
}
