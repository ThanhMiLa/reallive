package com.reallive.streamservice.stream;

import com.reallive.streamservice.common.configuration.MediaProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

public final class MediaTestSupport {
    public static final KeyPair KEYS = generateKeys();
    private MediaTestSupport() {
    }
    public static KeyPair generateKeys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
    public static MediaProperties properties() {
        var properties = new MediaProperties();
        properties.setPrivateKeyBase64(Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
        properties.setPublicKeyBase64(Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        return properties;
    }
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("stream.media.private-key-base64", () -> properties().getPrivateKeyBase64());
        registry.add("stream.media.public-key-base64", () -> properties().getPublicKeyBase64());
        registry.add("app.service.user", () -> "http://127.0.0.1:1/user");
        registry.add("app.service.channel", () -> "http://127.0.0.1:1/channel");
    }
}
