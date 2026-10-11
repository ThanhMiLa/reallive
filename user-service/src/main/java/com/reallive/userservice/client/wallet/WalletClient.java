package com.reallive.userservice.client.wallet;


import com.reallive.userservice.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import java.util.UUID;

@FeignClient(
        name = "payment-service",
        url = "${app.service.payment}")
public interface WalletClient {
    @PostMapping(
            value = "/internal/api/wallets/create/{userId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<WalletCreationResponse>> createWallet(@PathVariable UUID userId);
}
