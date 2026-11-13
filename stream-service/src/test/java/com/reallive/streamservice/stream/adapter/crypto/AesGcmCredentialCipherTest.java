package com.reallive.streamservice.stream.adapter.crypto;

import com.reallive.streamservice.common.configuration.MediaKeyMaterial;
import com.reallive.streamservice.stream.MediaTestSupport;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AesGcmCredentialCipherTest {

    private final AesGcmCredentialCipher cipher = new AesGcmCredentialCipher(new MediaKeyMaterial(
            (RSAPrivateKey) MediaTestSupport.KEYS.getPrivate(),
            (RSAPublicKey) MediaTestSupport.KEYS.getPublic(),
            new SecretKeySpec(Base64.getDecoder().decode(MediaTestSupport.CREDENTIAL_ENCRYPTION_KEY_BASE64), "AES")
    ));

    @Test
    void roundTripAndAadBindingWorkAndNeverEchoPlaintext() {
        UUID channelId = UUID.randomUUID();
        UUID credId = UUID.randomUUID();
        String secret = "eyJhbGciOiJSUzI1NiJ9.secret-jwt-payload.signature";

        String encrypted1 = cipher.encrypt(secret, channelId, credId, 1L, "PUBLISH");
        String encrypted2 = cipher.encrypt(secret, channelId, credId, 1L, "PUBLISH");

        assertNotEquals(encrypted1, encrypted2);
        assertFalse(encrypted1.contains(secret));
        assertEquals(secret, cipher.decrypt(encrypted1, channelId, credId, 1L, "PUBLISH"));

        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(encrypted1, UUID.randomUUID(), credId, 1L, "PUBLISH"));
        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(encrypted1, channelId, credId, 2L, "PUBLISH"));
        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(encrypted1, channelId, credId, 1L, "RELAY_READ"));
        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt("corrupted", channelId, credId, 1L, "PUBLISH"));
    }
}
