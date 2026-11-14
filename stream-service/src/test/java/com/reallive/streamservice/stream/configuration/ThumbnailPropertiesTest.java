package com.reallive.streamservice.stream.configuration;

import com.reallive.streamservice.common.configuration.MediaProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThumbnailPropertiesTest {

    private final MediaProperties media = new MediaProperties();

    @Test
    void acceptsValidEnabledConfiguration() {
        assertDoesNotThrow(() -> validProperties().validate(media));
    }

    @Test
    void rejectsInvalidRangesUrlsAndTokenLifetime() {
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

        MediaProperties shortReadTtl = new MediaProperties();
        shortReadTtl.setReadTokenTtl(Duration.ofSeconds(30));
        assertThrows(IllegalStateException.class, () -> validProperties().validate(shortReadTtl));
    }

    private ThumbnailProperties validProperties() {
        ThumbnailProperties properties = new ThumbnailProperties();
        properties.setEnabled(true);
        properties.setWorkerBaseUrl("http://media-worker:8090");
        properties.setInternalHlsBaseUrl("http://media-server:8888");
        return properties;
    }
}
