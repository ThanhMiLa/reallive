package com.reallive.streamservice.stream.client;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

public class StreamClientConfiguration {
    @Bean
    Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }
}
