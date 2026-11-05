package com.reallive.streamservice.stream.client.thumbnail;

import com.reallive.streamservice.stream.configuration.ThumbnailProperties;
import feign.Request;
import feign.Retryer;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class ThumbnailWorkerClientConfigurationTest {

    @Test
    void disablesAutomaticRetriesAndAppliesConfiguredTimeouts() {
        ThumbnailProperties properties = new ThumbnailProperties();
        properties.setWorkerConnectTimeout(Duration.ofSeconds(2));
        properties.setWorkerReadTimeout(Duration.ofSeconds(5));
        ThumbnailWorkerClientConfiguration configuration = new ThumbnailWorkerClientConfiguration();

        Request.Options options = configuration.requestOptions(properties);

        assertEquals(2_000, options.connectTimeout());
        assertEquals(5_000, options.readTimeout());
        assertFalse(options.isFollowRedirects());
        assertSame(Retryer.NEVER_RETRY, configuration.retryer());
    }
}
