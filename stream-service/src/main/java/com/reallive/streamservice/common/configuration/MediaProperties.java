package com.reallive.streamservice.common.configuration;

import lombok.Data;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
@ToString(onlyExplicitlyIncluded = true)
@ConfigurationProperties(prefix = "stream.media")
public class MediaProperties {
    String privateKeyBase64;
    String publicKeyBase64;
    String keyId = "stream-rs256-v1";
    String issuer = "reallive-stream";
    String audience = "reallive-media";
    Duration publishTokenTtl = Duration.ofHours(8);
    Duration readTokenTtl = Duration.ofMinutes(5);
    Duration controlTokenTtl = Duration.ofMinutes(5);
    String rtmpPublicUrl = "rtmp://localhost:1935/live";
    String hlsPublicBaseUrl = "http://localhost:8898";
}
