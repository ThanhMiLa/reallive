package com.reallive.streamservice.common.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfiguration {
    @Bean
    MediaKeyMaterial mediaKeyMaterial(MediaProperties properties) {
        validateProperties(properties);
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(properties.getPrivateKeyBase64())));
            var publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(properties.getPublicKeyBase64())));
            if (privateKey.getModulus().bitLength() < 2048 || !privateKey.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalArgumentException();
            }
            Signature signature = Signature.getInstance("SHA256withRSA");
            byte[] challenge = "reallive-media-key-check".getBytes(StandardCharsets.UTF_8);
            signature.initSign(privateKey);
            signature.update(challenge);
            byte[] signed = signature.sign();
            signature.initVerify(publicKey);
            signature.update(challenge);
            if (!signature.verify(signed)) {
                throw new IllegalArgumentException();
            }
            return new MediaKeyMaterial(privateKey, publicKey);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid media RSA configuration: require matching RSA keys of at least 2048 bits");
        }
    }

    private void validateProperties(MediaProperties properties) {
        if (blank(properties.getPrivateKeyBase64()) || blank(properties.getPublicKeyBase64())
                || blank(properties.getKeyId()) || blank(properties.getIssuer()) || blank(properties.getAudience())
                || !positive(properties.getPublishTokenTtl()) || !positive(properties.getReadTokenTtl())
                || !positive(properties.getControlTokenTtl())
                || !validUrl(properties.getRtmpPublicUrl(), Set.of("rtmp", "rtmps"))
                || !validUrl(properties.getHlsPublicBaseUrl(), Set.of("http", "https"))) {
            throw new IllegalStateException("Missing or invalid media keys, identity, TTL or public URLs configuration");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank() || value.startsWith("${");
    }

    private boolean positive(Duration value) {
        return value != null && !value.isNegative() && value.getSeconds() > 0;
    }

    private boolean validUrl(String value, Set<String> schemes) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && uri.getScheme() != null && schemes.contains(uri.getScheme())
                    && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (Exception exception) {
            return false;
        }
    }
}
