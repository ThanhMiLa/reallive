package com.reallive.streamservice.common.configuration;

import static org.junit.jupiter.api.Assertions.*;

import com.reallive.streamservice.stream.MediaTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.Base64;

class MediaConfigurationTest {
    private final MediaConfiguration configuration = new MediaConfiguration();

    @Test
    void loadsMatchingKeysInternalTokenAndDefaults() {
        var properties = MediaTestSupport.properties();
        var keys = configuration.mediaKeyMaterial(properties);
        assertEquals(2048, keys.publicKey().getModulus().bitLength());
        assertEquals(Duration.ofMinutes(5), properties.getReadTokenTtl());
        assertEquals(Duration.ofMinutes(5), properties.getControlTokenTtl());
        assertEquals("rtmp://localhost:1935/ingest", properties.getRtmpPublicUrl());
        assertFalse(properties.toString().contains(properties.getPrivateKeyBase64()));
        assertFalse(properties.toString().contains(properties.getCallbackInternalToken()));
        assertFalse(keys.toString().contains(keys.privateKey().getPrivateExponent().toString()));
    }

    @Test
    void rejectsMismatchedMalformedOrMissingSecretsWithoutEchoingThem() {
        var properties = MediaTestSupport.properties();
        properties.setPublicKeyBase64(
                Base64.getEncoder()
                        .encodeToString(MediaTestSupport.generateKeys().getPublic().getEncoded()));
        var mismatch =
                assertThrows(
                        IllegalStateException.class,
                        () -> configuration.mediaKeyMaterial(properties));
        assertFalse(mismatch.getMessage().contains(properties.getPrivateKeyBase64()));

        var badPrivate = MediaTestSupport.properties();
        badPrivate.setPrivateKeyBase64("bad-private-secret-value");
        var malformed =
                assertThrows(
                        IllegalStateException.class,
                        () -> configuration.mediaKeyMaterial(badPrivate));
        assertFalse(malformed.getMessage().contains("bad-private-secret-value"));

        var shortToken = MediaTestSupport.properties();
        shortToken.setCallbackInternalToken("short");
        assertThrows(IllegalStateException.class, () -> configuration.mediaKeyMaterial(shortToken));

        assertThrows(
                IllegalStateException.class,
                () -> configuration.mediaKeyMaterial(new MediaProperties()));
    }

    @Test
    void rejectsInvalidTtlAndUrls() {
        var properties = MediaTestSupport.properties();
        properties.setReadTokenTtl(Duration.ZERO);
        assertThrows(IllegalStateException.class, () -> configuration.mediaKeyMaterial(properties));
        properties.setReadTokenTtl(Duration.ofMinutes(5));
        properties.setRtmpPublicUrl("http://wrong-ingest");
        assertThrows(IllegalStateException.class, () -> configuration.mediaKeyMaterial(properties));
        properties.setRtmpPublicUrl("rtmp://localhost/ingest?token=secret");
        assertThrows(IllegalStateException.class, () -> configuration.mediaKeyMaterial(properties));
        properties.setRtmpPublicUrl("rtmp://localhost:1935/ingest");
    }

    @Test
    void startupFailsWhenRequiredConfigurationIsMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(MediaConfiguration.class)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
