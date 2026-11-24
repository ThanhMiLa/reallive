package com.reallive.paymentservice.deposit.adapter.vnpay;

public record VnpayPaymentResult(
        String responseCode,
        String transactionStatus,
        String transactionNo
) {}
