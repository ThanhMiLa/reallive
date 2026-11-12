package com.reallive.streamservice.client.thumbnail;

import com.reallive.streamservice.stream.configuration.ThumbnailProperties;
import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;

public class ThumbnailWorkerClientConfiguration {

    @Bean
    Retryer retryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    Request.Options requestOptions(ThumbnailProperties properties) {
        return new Request.Options(
                properties.getWorkerConnectTimeout(),
                properties.getWorkerReadTimeout(),
                false
        );
    }
}
