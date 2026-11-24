package com.reallive.paymentservice.deposit.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "vnpay.reconcile", name = "enabled", havingValue = "true", matchIfMissing = false)
public class VnpaySchedulingConfiguration {
}
