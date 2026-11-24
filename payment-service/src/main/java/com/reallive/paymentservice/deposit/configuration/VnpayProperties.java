package com.reallive.paymentservice.deposit.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties("vnpay")
public record VnpayProperties(
        String tmnCode,
        String hashSecret,
        URI payUrl,
        URI apiUrl,
        String returnUrl,
        String version,
        String currCode,
        String locale,
        String orderType,
        int expireMinutes,
        Reconcile reconcile
) {
    public record Reconcile(
            boolean enabled,
            long fixedDelayMs,
            int minAgeMinutes,
            int expireBufferMinutes,
            int batchSize,
            long httpTimeoutMs,
            String ipAddress
    ) {}
}
