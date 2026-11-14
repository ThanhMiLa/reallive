package com.reallive.streamservice.common.configuration;

import lombok.AccessLevel;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
@ToString(onlyExplicitlyIncluded = true)
@ConfigurationProperties(prefix = "stream.media")
public class MediaProperties {

    String privateKeyBase64;
    String publicKeyBase64;

    @ToString.Include String keyId = "stream-rs256-v1";

    @ToString.Include String issuer = "reallive-stream";

    @ToString.Include String audience = "reallive-media";

    @ToString.Include Duration readTokenTtl = Duration.ofMinutes(5);

    @ToString.Include Duration controlTokenTtl = Duration.ofMinutes(5);

    @ToString.Include String rtmpPublicUrl = "rtmp://localhost:1935/ingest";

    @ToString.Include String hlsPublicBaseUrl = "http://localhost:8898";
}
