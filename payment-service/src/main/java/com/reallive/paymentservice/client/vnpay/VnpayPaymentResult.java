package com.reallive.paymentservice.client.vnpay;

public record VnpayPaymentResult(
        String responseCode,
        String transactionStatus,
        String transactionNo
) {}
