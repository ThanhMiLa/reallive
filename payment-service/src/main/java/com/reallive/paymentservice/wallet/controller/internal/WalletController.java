package com.reallive.paymentservice.wallet.controller.internal;

import com.reallive.paymentservice.common.dto.ApiResponse;
import com.reallive.paymentservice.wallet.dto.response.WalletCreationResponse;
import com.reallive.paymentservice.wallet.service.WalletService;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@AllArgsConstructor
@RequestMapping("/internal")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class WalletController {
    WalletService walletService;

    @PostMapping("/api/wallets/create/{userId}")
    public ResponseEntity<ApiResponse<WalletCreationResponse>> createWallet(@PathVariable UUID userId) {
        WalletCreationResponse response = walletService.createWallet(userId);
        return ResponseEntity.ok(ApiResponse.<WalletCreationResponse>builder()
                .status(200)
                .code(0)
                .message("Wallet created successfully")
                .result(response)
                .timestamp(java.time.Instant.now().toString())
                .build());
    }
}
