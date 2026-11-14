package com.reallive.streamservice.stream.configuration;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;

@Data
@ToString(onlyExplicitlyIncluded = true)
@ConfigurationProperties(prefix = "stream.ccu")
public class CcuProperties {
    private boolean enabled;
    private Path controlTokenDirectory;
    private long tokenPublishDelay = 60_000;
}
