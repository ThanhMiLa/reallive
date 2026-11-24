package com.reallive.paymentservice.deposit.dto.response;

import com.reallive.paymentservice.deposit.entity.TransactionStatus;

import java.time.Instant;
import java.util.UUID;

public record CreateVnpayDepositResponse(
        UUID paymentId,
        long orderCode,
        String packageCode,
        long amountVnd,
        long coinAmount,
        TransactionStatus status,
        Instant expiresAt,
        String paymentUrl
) {}
