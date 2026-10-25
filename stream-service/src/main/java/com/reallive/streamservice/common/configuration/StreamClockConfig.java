package com.reallive.streamservice.common.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class StreamClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock streamClock() {
        return Clock.systemUTC();
    }
}
