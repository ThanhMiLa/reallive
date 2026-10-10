package com.reallive.notificationservice.email.service;

import com.reallive.notificationservice.common.exception.AppException;
import com.reallive.notificationservice.common.exception.ErrorCode;
import com.reallive.notificationservice.email.EmailDeliveryTestSupport;
import com.reallive.notificationservice.email.configuration.EmailDeliveryProperties;
import com.reallive.notificationservice.email.repository.EmailDeliveryEventRepository;
import com.reallive.notificationservice.email.repository.EmailNotificationRepository;
import com.reallive.notificationservice.email.service.impl.SendGridWebhookServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SendGridWebhookServiceTest {

    @Mock
    EmailNotificationRepository notifications;
    @Mock
    EmailDeliveryEventRepository deliveryEvents;

    EmailDeliveryProperties properties;
    SendGridWebhookService service;
    KeyPair keys;
    String timestamp = "1700000000";

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keys = generator.generateKeyPair();
        properties = EmailDeliveryTestSupport.properties();
        properties.getSendgrid().setWebhookVerificationPublicKey(Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        service = new SendGridWebhookServiceImpl(properties, JsonMapper.builder().build(), notifications, deliveryEvents);
    }

    @Test
    void verifiesOriginalUtf8BytesAndAcknowledgesUnrelatedEvents() throws Exception {
        byte[] body = "[ {\"event\":\"delivered\",\"email\":\"other@example.com\",\"reason\":\"Tiếng Việt\"} ]\r\n"
                .getBytes(StandardCharsets.UTF_8);
        service.receive(body, sign(body), timestamp);
        verifyNoInteractions(notifications, deliveryEvents);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-base64", "YWJj"})
    void rejectsMissingOrMalformedSignatureBeforeReadingOrWriting(String signature) {
        assertError(ErrorCode.EMAIL_WEBHOOK_UNAUTHENTICATED, "not JSON".getBytes(StandardCharsets.UTF_8), signature, timestamp);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "-1", "invalid", "12345678901234567890"})
    void rejectsInvalidTimestampHeader(String header) throws Exception {
        byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
        assertError(ErrorCode.EMAIL_WEBHOOK_UNAUTHENTICATED, body, sign(body), header);
    }

    @Test
    void rejectsChangedBodyWithoutReserializingIt() throws Exception {
        byte[] original = "[]\n".getBytes(StandardCharsets.UTF_8);
        assertError(ErrorCode.EMAIL_WEBHOOK_UNAUTHENTICATED, "[]".getBytes(StandardCharsets.UTF_8), sign(original), timestamp);
    }

    @Test
    void rejectsChangedSignedTimestamp() throws Exception {
        byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
        assertError(ErrorCode.EMAIL_WEBHOOK_UNAUTHENTICATED, body, sign(body), "1700000001");
    }

    @Test
    void rejectsSignatureFromAnotherKey() throws Exception {
        byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
        String signature = sign(body);
        keys = KeyPairGenerator.getInstance("EC").generateKeyPair();
        properties.getSendgrid().setWebhookVerificationPublicKey(Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        assertError(ErrorCode.EMAIL_WEBHOOK_UNAUTHENTICATED, body, signature, timestamp);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "YWJj"})
    void invalidPublicKeyFailsClosed(String key) throws Exception {
        byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
        properties.getSendgrid().setWebhookVerificationPublicKey(key);
        assertError(ErrorCode.EMAIL_WEBHOOK_CONFIGURATION_INVALID, body, sign(body), timestamp);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not JSON", "{}", "null", "[null]", "[{}]",
            "[{\"event\":\"delivered\",\"eventId\":\"bad-id\"}]"})
    void signedMalformedEventsDoNotAccessDatabase(String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        assertError(ErrorCode.EMAIL_WEBHOOK_INVALID, body, sign(body), timestamp);
    }

    private String sign(byte[] body) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate());
        signer.update(timestamp.getBytes(StandardCharsets.UTF_8));
        signer.update(body);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private void assertError(ErrorCode error, byte[] body, String signature, String header) {
        AppException exception = assertThrows(AppException.class, () -> service.receive(body, signature, header));
        assertEquals(error, exception.getErrorCode());
        verifyNoInteractions(notifications, deliveryEvents);
    }
}
