package com.reallive.streamservice.stream.configuration;

import com.reallive.streamservice.common.configuration.MediaProperties;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ThumbnailProperties.class)
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ThumbnailConfiguration {

    ThumbnailProperties properties;
    MediaProperties mediaProperties;

    @PostConstruct
    void validateConfiguration() {
        if (properties.isEnabled()) {
            properties.validate(mediaProperties);
        }
    }
}
