package com.reallive.streamservice.stream.configuration;

import com.reallive.streamservice.common.configuration.MediaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThumbnailPropertiesTest {

    private final MediaProperties media = new MediaProperties();

    @Test
    void dispatchConfigurationUsesWorkerTokenWhenCallbackTokenDiffers() throws IOException {
        var environment = dispatchEnvironment("worker-secret-0123456789abcdef012345",
                "callback-secret-0123456789abcdef012345");

        assertEquals("worker-secret-0123456789abcdef012345",
                environment.getProperty("stream.thumbnail.internal-token"));
    }

    @Test
    void callbackTokenCannotSubstituteForMissingWorkerToken() throws IOException {
        var environment = dispatchEnvironment("", "callback-secret-0123456789abcdef012345");

        assertEquals("", environment.getProperty("stream.thumbnail.internal-token"));
        ThumbnailProperties properties = validProperties();
        properties.setInternalToken(environment.getProperty("stream.thumbnail.internal-token"));
        assertThrows(IllegalStateException.class, () -> properties.validate(media));
    }

    private StandardEnvironment dispatchEnvironment(String workerToken, String callbackToken) throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("testTokens", Map.of(
                "MEDIA_WORKER_INTERNAL_TOKEN", workerToken,
                "STREAM_SERVICE_INTERNAL_TOKEN", callbackToken
        )));
        for (var source : new YamlPropertySourceLoader().load("dev", new ClassPathResource("application-dev.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    @Test
    void acceptsValidEnabledConfiguration() {
        assertDoesNotThrow(() -> validProperties().validate(media));
    }

    @Test
    void rejectsInvalidRangesUrlsTokensAndTokenLifetime() {
        ThumbnailProperties zeroInterval = validProperties();
        zeroInterval.setInterval(Duration.ZERO);
        assertThrows(IllegalStateException.class, () -> zeroInterval.validate(media));

        ThumbnailProperties oversizedBatch = validProperties();
        oversizedBatch.setBatchSize(51);
        assertThrows(IllegalStateException.class, () -> oversizedBatch.validate(media));

        ThumbnailProperties workerUrlWithPath = validProperties();
        workerUrlWithPath.setWorkerBaseUrl("http://media-worker:8090/internal");
        assertThrows(IllegalStateException.class, () -> workerUrlWithPath.validate(media));

        ThumbnailProperties hlsUrlWithQuery = validProperties();
        hlsUrlWithQuery.setInternalHlsBaseUrl("http://media-server:8888?token=secret");
        assertThrows(IllegalStateException.class, () -> hlsUrlWithQuery.validate(media));

        ThumbnailProperties shortToken = validProperties();
        shortToken.setInternalToken("too-short");
        assertThrows(IllegalStateException.class, () -> shortToken.validate(media));

        MediaProperties shortReadTtl = new MediaProperties();
        shortReadTtl.setReadTokenTtl(Duration.ofSeconds(30));
        assertThrows(IllegalStateException.class, () -> validProperties().validate(shortReadTtl));
    }

    private ThumbnailProperties validProperties() {
        ThumbnailProperties properties = new ThumbnailProperties();
        properties.setEnabled(true);
        properties.setInternalToken("test-internal-token-0123456789abcdef");
        properties.setWorkerBaseUrl("http://media-worker:8090");
        properties.setInternalHlsBaseUrl("http://media-server:8888");
        return properties;
    }
}
