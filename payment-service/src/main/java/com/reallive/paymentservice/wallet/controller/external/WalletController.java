package com.reallive.paymentservice.wallet.controller.external;

import com.reallive.paymentservice.common.dto.ApiResponse;
import com.reallive.paymentservice.common.util.AuthenticationUserId;
import com.reallive.paymentservice.wallet.dto.response.WalletBalanceResponse;
import com.reallive.paymentservice.wallet.service.WalletService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class WalletController {
    WalletService walletService;

    @PostMapping("/me")
    public ApiResponse<WalletBalanceResponse> createWallet(@AuthenticationPrincipal Jwt jwt) {
        return response(walletService.createWallet(AuthenticationUserId.getAuthenticatedUserId(jwt)),
                "Wallet ready");
    }

    @GetMapping("/me")
    public ApiResponse<WalletBalanceResponse> getBalance(@AuthenticationPrincipal Jwt jwt) {
        return response(walletService.getBalance(AuthenticationUserId.getAuthenticatedUserId(jwt)),
                "Wallet balance retrieved successfully");
    }

    private static ApiResponse<WalletBalanceResponse> response(WalletBalanceResponse wallet, String message) {
        return ApiResponse.<WalletBalanceResponse>builder()
                .status(200)
                .code(0)
                .message(message)
                .result(wallet)
                .timestamp(Instant.now().toString())
                .build();
    }
}
