package com.reallive.streamservice.stream.configuration;

import com.reallive.streamservice.common.configuration.MediaProperties;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CcuProperties.class)
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CcuConfiguration {

    CcuProperties properties;
    MediaProperties media;

    @PostConstruct
    void validate() {
        if (!properties.isEnabled()) {
            return;
        }
        String token = properties.getCallbackToken();
        var directory = properties.getControlTokenDirectory();
        if (token == null || token.length() < 32 || token.length() > 16_384
                || token.chars().anyMatch(Character::isWhitespace)
                || directory == null || directory.toString().isBlank() || !directory.isAbsolute()) {
            throw new IllegalStateException("CCU requires a non-whitespace 32+ character callback token and an absolute control token directory");
        }
        long delay = properties.getTokenPublishDelay();
        if (delay < 1_000 || delay > Duration.ofHours(24).toMillis()
                || media.getControlTokenTtl() == null
                || media.getControlTokenTtl().compareTo(Duration.ofMillis(delay).plusSeconds(10)) <= 0) {
            throw new IllegalStateException("CCU control JWT TTL must exceed its refresh interval plus the 10-second hook budget");
        }
    }
}
