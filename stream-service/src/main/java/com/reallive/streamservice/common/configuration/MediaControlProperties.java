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
    Paths paths = new Paths();

    @Data
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static class Paths {
        String configGet = "/v3/config/paths/get/";
        String configAdd = "/v3/config/paths/add/";
        String configDelete = "/v3/config/paths/delete/";
        String configList = "/v3/config/paths/list";
        String statusGet = "/v3/paths/get/";
        String statusList = "/v3/paths/list";
        String connsKick = "/v3/%s/conns/kick/";
        String streamPrefix = "live/";
    }
}
