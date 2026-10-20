package com.reallive.notificationservice.email.controller;

import com.reallive.notificationservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.notificationservice.email.dto.message.EmailType;
import com.reallive.notificationservice.email.entity.EmailDeliveryEventEntity;
import com.reallive.notificationservice.email.entity.EmailDeliveryEventType;
import com.reallive.notificationservice.email.entity.EmailNotificationEntity;
import com.reallive.notificationservice.email.entity.EmailNotificationStatus;
import com.reallive.notificationservice.email.repository.EmailDeliveryEventRepository;
import com.reallive.notificationservice.email.repository.EmailNotificationRepository;
import com.reallive.notificationservice.email.service.SendGridWebhookService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:email_webhooks;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SendGridWebhookIntegrationTest {
    @MockitoBean
    private RedisAuthoritiesReader authAuthoritiesReader;

    @BeforeEach
    void setUpAuthAuthorities() {
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    static final String PATH = "/api/webhooks/sendgrid";
    static final String TIMESTAMP = "1700000000";
    static final KeyPair KEYS = keys();

    @Autowired
    MockMvc mvc;
    @Autowired
    EmailNotificationRepository notifications;
    @MockitoSpyBean
    EmailDeliveryEventRepository deliveryEvents;
    @Autowired
    SendGridWebhookService service;

    EmailNotificationEntity notification;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("notification.email.sendgrid.webhook-verification-public-key",
                () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
    }

    @BeforeEach
    void setUp() {
        deliveryEvents.deleteAll();
        notifications.deleteAll();
        notification = notification();
    }

    @ParameterizedTest
    @EnumSource(EmailDeliveryEventType.class)
    void signedEventWithoutUserCookiePersistsHistory(EmailDeliveryEventType type) throws Exception {
        receive(event(type.name().toLowerCase(Locale.ROOT), "provider-event", 1700000000))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1000));
        EmailDeliveryEventEntity saved = deliveryEvents.findBySgEventId("provider-event").orElseThrow();
        assertEquals(notification.getId(), saved.getNotification().getId());
        assertEquals(type, saved.getEventType());
        assertEquals(Instant.ofEpochSecond(1700000000), saved.getProviderTimestamp());
        assertEquals("provider-message", saved.getSgMessageId());
        assertEquals("250 OK", saved.getResponse());
        assertEquals("2.0.0", saved.getSmtpStatus());
        assertNotNull(saved.getReceivedAt());
        assertNotNull(saved.getCreatedAt());
        assertEquals(EmailNotificationStatus.PROCESSING, notifications.findById(notification.getId()).orElseThrow().getStatus());
    }

    @Test
    void duplicateEventsWithinBatchAndAcrossRequestsAreAcknowledgedOnce() throws Exception {
        String event = event("delivered", "duplicate", 1700000000);
        receive(batch(event, event)).andExpect(status().isOk());
        receive(event).andExpect(status().isOk());
        assertEquals(1, deliveryEvents.count());
    }

    @Test
    void concurrentRedeliveryCreatesOneEvent() throws Exception {
        concurrently(event("delivered", "concurrent", 1700000000), event("delivered", "concurrent", 1700000000));
        assertEquals(1, deliveryEvents.count());
    }

    @Test
    void overlappingBatchesLockNotificationsInSameOrder() throws Exception {
        String first = event("processed", "first", 1700000000);
        EmailNotificationEntity another = notification();
        String second = event("delivered", "second", 1700000010)
                .replace(notification.getEventId().toString(), another.getEventId().toString());
        concurrently(batch(first, second), batch(second, first));
        assertEquals(2, deliveryEvents.count());
    }

    @Test
    void eventsReceivedOutOfOrderRemainSeparateAndReadChronologically() throws Exception {
        receive(event("delivered", "later", 1700000010)).andExpect(status().isOk());
        receive(event("processed", "earlier", 1700000000)).andExpect(status().isOk());
        var events = deliveryEvents.findByNotificationIdOrderByProviderTimestampAscReceivedAtAsc(notification.getId(), PageRequest.of(0, 10));
        assertEquals(2, events.getTotalElements());
        assertEquals(EmailDeliveryEventType.PROCESSED, events.getContent().getFirst().getEventType());
        assertEquals(EmailDeliveryEventType.DELIVERED, events.getContent().getLast().getEventType());
    }

    @Test
    void databaseFailureRollsBackWholeBatchAndAllowsProviderRetry() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> {
            if (writes.incrementAndGet() == 2) {
                throw new DataAccessResourceFailureException("test database failure");
            }
            EmailDeliveryEventEntity saved = deliveryEvents.save(invocation.getArgument(0));
            deliveryEvents.flush();
            return saved;
        }).when(deliveryEvents).saveAndFlush(any(EmailDeliveryEventEntity.class));
        String batch = batch(event("processed", "first", 1700000000), event("delivered", "second", 1700000010));
        receive(batch).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1009));
        assertEquals(0, deliveryEvents.count());
        receive(batch).andExpect(status().isOk());
        assertEquals(2, deliveryEvents.count());
    }

    @Test
    void malformedSecondEventDoesNotPartiallyPersistBatch() throws Exception {
        String first = event("processed", "first", 1700000000);
        String invalid = event("delivered", "invalid", 1700000010).replace("\"timestamp\":1700000010,", "");
        receive(batch(first, invalid)).andExpect(status().isBadRequest());
        assertEquals(0, deliveryEvents.count());
    }

    @Test
    void unrelatedEmailsUnknownCorrelationsAndUnsupportedEventsAreAcknowledged() throws Exception {
        receive("[{\"event\":\"delivered\",\"timestamp\":1700000000}]").andExpect(status().isOk());
        receive(event("delivered", "unknown", 1700000000).replace(notification.getEventId().toString(), UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        receive(event("open", "unsupported", 1700000000)).andExpect(status().isOk());
        assertEquals(0, deliveryEvents.count());
    }

    @Test
    void emptyBatchIsAcknowledged() throws Exception {
        receive("[]").andExpect(status().isOk());
        assertEquals(0, deliveryEvents.count());
    }

    @Test
    void missingSignatureIsRejected() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(event("delivered", "bad", 1700000000)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1010));
        assertEquals(0, deliveryEvents.count());
    }

    @Test
    void webhookRejectsInvalidUserCookieEvenWithValidProviderSignature() throws Exception {
        byte[] body = event("delivered", "cookie-independent", 1700000000).getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/notification" + PATH).contextPath("/notification").servletPath(PATH)
                        .cookie(new Cookie("access_token", "invalid-user-cookie"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Twilio-Email-Event-Webhook-Signature", sign(body))
                        .header("X-Twilio-Email-Event-Webhook-Timestamp", TIMESTAMP).content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
        assertEquals(0, deliveryEvents.count());
    }

    @Test
    void tamperedBodyIsRejected() throws Exception {
        byte[] original = event("processed", "tampered", 1700000000).getBytes(StandardCharsets.UTF_8);
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Twilio-Email-Event-Webhook-Signature", sign(original))
                        .header("X-Twilio-Email-Event-Webhook-Timestamp", TIMESTAMP)
                        .content(new String(original, StandardCharsets.UTF_8).replace("processed", "delivered")))
                .andExpect(status().isUnauthorized());
        assertEquals(0, deliveryEvents.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-id", "missing-time", "negative-time", "overflow-time", "long-status", "bad-event-id"})
    void invalidCorrelatedEventDoesNotModifyHistory(String failure) throws Exception {
        String json = event("delivered", "invalid", 1700000000);
        json = switch (failure) {
            case "missing-id" -> json.replace("\"sg_event_id\":\"invalid\",", "");
            case "missing-time" -> json.replace("\"timestamp\":1700000000,", "");
            case "negative-time" -> json.replace("1700000000", "-1");
            case "overflow-time" -> json.replace("1700000000", "9223372036854775807");
            case "long-status" -> json.replace("2.0.0", "x".repeat(51));
            default -> json.replace(notification.getEventId().toString(), "bad-id");
        };
        receive(json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1011));
        assertEquals(0, deliveryEvents.count());
    }

    @Test
    void cleansAndBoundsOptionalDetails() throws Exception {
        String json = event("bounce", "bounded", 1700000000).replace("\"response\":\"250 OK\"",
                "\"response\":\"250\\r\\nOK\",\"reason\":\"" + "x".repeat(3000) + "\"");
        receive(json).andExpect(status().isOk());
        var saved = deliveryEvents.findBySgEventId("bounded").orElseThrow();
        assertEquals("250  OK", saved.getResponse());
        assertEquals(2048, saved.getReason().length());
    }

    @Test
    void onlyExactPostWebhookIsPublic() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/extra")).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/events")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/private")).andExpect(status().isUnauthorized());
    }

    private EmailNotificationEntity notification() {
        return notifications.saveAndFlush(EmailNotificationEntity.builder()
                .eventId(UUID.randomUUID()).userId(UUID.randomUUID()).emailType(EmailType.EMAIL_VERIFICATION)
                .recipientEmail("recipient@example.com").templateId("d-test-template")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .status(EmailNotificationStatus.PROCESSING).attemptCount(1).build());
    }

    private void concurrently(String first, String second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var requests = List.of(first, second).stream().map(json -> executor.submit(() -> {
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                String signature = sign(body);
                ready.countDown();
                start.await();
                service.receive(body, signature, TIMESTAMP);
                return true;
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var request : requests) {
                assertTrue(request.get(10, TimeUnit.SECONDS));
            }
        }
    }

    private ResultActions receive(String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("X-Twilio-Email-Event-Webhook-Signature", sign(body))
                .header("X-Twilio-Email-Event-Webhook-Timestamp", TIMESTAMP).content(body));
    }

    private String event(String type, String providerId, long timestamp) {
        return """
                [{"event":"%s","eventId":"%s","sg_event_id":"%s","timestamp":%d,
                  "sg_message_id":"provider-message","response":"250 OK","status":"2.0.0","extra":"ignored"}]
                """.formatted(type, notification.getEventId(), providerId, timestamp).strip();
    }

    private String batch(String first, String second) {
        return "[" + first.substring(1, first.length() - 1) + "," + second.substring(1, second.length() - 1) + "]";
    }

    private static KeyPair keys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sign(byte[] body) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(KEYS.getPrivate());
        signer.update(TIMESTAMP.getBytes(StandardCharsets.UTF_8));
        signer.update(body);
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}
