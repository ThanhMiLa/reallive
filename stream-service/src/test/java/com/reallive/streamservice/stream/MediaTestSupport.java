package com.reallive.streamservice.stream;

import com.reallive.streamservice.common.configuration.MediaProperties;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

public final class MediaTestSupport {

    public static final KeyPair KEYS = generateKeys();
    public static final String CREDENTIAL_ENCRYPTION_KEY_BASE64 = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    public static final String CALLBACK_INTERNAL_TOKEN = "media-hook-internal-token-0123456789abcdef";

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
        properties.setCredentialEncryptionKey(CREDENTIAL_ENCRYPTION_KEY_BASE64);
        properties.setCallbackInternalToken(CALLBACK_INTERNAL_TOKEN);
        return properties;
    }

    public static void register(DynamicPropertyRegistry registry) {
        MediaProperties props = properties();
        registry.add("stream.media.private-key-base64", props::getPrivateKeyBase64);
        registry.add("stream.media.public-key-base64", props::getPublicKeyBase64);
        registry.add("stream.media.credential-encryption-key", props::getCredentialEncryptionKey);
        registry.add("stream.media.callback-internal-token", props::getCallbackInternalToken);
        registry.add("app.service.user", () -> "http://127.0.0.1:1/user");
        registry.add("app.service.channel", () -> "http://127.0.0.1:1/channel");
    }
}
