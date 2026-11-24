package com.reallive.paymentservice.deposit.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateVnpayDepositRequest(
        @NotBlank String packageCode
) {}
