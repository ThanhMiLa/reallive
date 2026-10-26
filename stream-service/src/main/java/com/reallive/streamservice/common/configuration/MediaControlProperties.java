package com.reallive.streamservice.common.configuration;

import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
@ConfigurationProperties(prefix = "stream.media.control-api")
public class MediaControlProperties {

    String baseUrl = "http://localhost:9997";
    Duration connectTimeout = Duration.ofSeconds(2);
    Duration requestTimeout = Duration.ofSeconds(5);
}
