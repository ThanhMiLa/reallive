package com.reallive.paymentservice.deposit.controller;

import com.reallive.paymentservice.common.dto.ApiResponse;
import com.reallive.paymentservice.common.util.AuthenticationUserId;
import com.reallive.paymentservice.deposit.dto.request.CreateVnpayDepositRequest;
import com.reallive.paymentservice.deposit.dto.response.CoinPackageResponse;
import com.reallive.paymentservice.deposit.dto.response.CreateVnpayDepositResponse;
import com.reallive.paymentservice.deposit.dto.response.DepositStatusResponse;
import com.reallive.paymentservice.deposit.service.DepositService;
import com.reallive.paymentservice.deposit.util.CheckoutIpAddress;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/deposits")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DepositController {

    DepositService depositService;

    @GetMapping("/packages")
    public ApiResponse<List<CoinPackageResponse>> getPackages() {
        return response(depositService.getPackages(), "Deposit packages retrieved successfully");
    }

    @PostMapping("/vnpay")
    public ApiResponse<CreateVnpayDepositResponse> createVnpayDeposit(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateVnpayDepositRequest request,
            HttpServletRequest servletRequest) {
        String ipAddress = CheckoutIpAddress.resolve(servletRequest.getHeader("X-Forwarded-For"),
                servletRequest.getRemoteAddr());
        return response(depositService.createVnpayDeposit(AuthenticationUserId.getAuthenticatedUserId(jwt),
                request.packageCode(), ipAddress), "Deposit created successfully");
    }

    @GetMapping("/{orderCode}")
    public ApiResponse<DepositStatusResponse> getStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable long orderCode) {
        return response(depositService.getStatus(AuthenticationUserId.getAuthenticatedUserId(jwt), orderCode),
                "Deposit status retrieved successfully");
    }

    private static <T> ApiResponse<T> response(T result, String message) {
        return ApiResponse.<T>builder()
                .status(200)
                .code(0)
                .message(message)
                .result(result)
                .timestamp(Instant.now().toString())
                .build();
    }
}
