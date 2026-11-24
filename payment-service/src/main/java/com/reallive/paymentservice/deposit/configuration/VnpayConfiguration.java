package com.reallive.paymentservice.deposit.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VnpayProperties.class)
public class VnpayConfiguration {

    @Bean
    Clock paymentClock() {
        return Clock.systemDefaultZone();
    }
}
