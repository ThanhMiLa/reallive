package com.reallive.streamservice.stream.configuration;

import com.reallive.streamservice.common.configuration.MediaProperties;
import lombok.AccessLevel;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
@ToString(onlyExplicitlyIncluded = true)
@ConfigurationProperties(prefix = "stream.thumbnail")
public class ThumbnailProperties {

    @ToString.Include
    boolean enabled;

    @ToString.Include
    Duration interval = Duration.ofMinutes(5);

    @ToString.Include
    Duration schedulerDelay = Duration.ofSeconds(10);

    @ToString.Include
    int batchSize = 50;

    @ToString.Include
    Duration jobDeadline = Duration.ofMinutes(4);

    @ToString.Include
    Duration workerConnectTimeout = Duration.ofSeconds(2);

    @ToString.Include
    Duration workerReadTimeout = Duration.ofSeconds(5);

    @ToString.Include
    String workerBaseUrl = "http://localhost:8090";

    @ToString.Include
    String internalHlsBaseUrl = "http://localhost:8898";

    String internalToken;

    /**
     * Validates the settings required to dispatch thumbnail jobs.
     *
     * @param mediaProperties shared media identity and token settings
     * @throws IllegalStateException when a required value or relationship is invalid
     */
    public void validate(MediaProperties mediaProperties) {
        if (!positive(interval) || !positive(schedulerDelay) || !positive(jobDeadline)
                || !positive(workerConnectTimeout) || !positive(workerReadTimeout)
                || batchSize < 1 || batchSize > 50
                || !origin(workerBaseUrl) || !origin(internalHlsBaseUrl)
                || blank(internalToken) || internalToken.length() < 32
                || mediaProperties.getReadTokenTtl() == null
                || mediaProperties.getReadTokenTtl().compareTo(jobDeadline) < 0) {
            throw new IllegalStateException("Invalid thumbnail dispatch configuration: require positive intervals, "
                    + "batch size 1-50, origin Worker/HLS URLs, a 32+ character internal token and a read token "
                    + "TTL at least as long as the job deadline");
        }
    }

    private boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }

    private boolean origin(String value) {
        if (blank(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String path = uri.getRawPath();
            return Set.of("http", "https").contains(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && (path == null || path.isEmpty() || path.equals("/"));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank() || value.startsWith("${");
    }
}
