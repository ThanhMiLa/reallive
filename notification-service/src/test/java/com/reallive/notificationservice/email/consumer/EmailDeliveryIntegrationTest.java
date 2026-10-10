package com.reallive.notificationservice.email.consumer;

import com.reallive.notificationservice.common.exception.AppException;
import com.reallive.notificationservice.email.EmailDeliveryTestSupport;
import com.reallive.notificationservice.email.configuration.EmailDeliveryProperties;
import com.reallive.notificationservice.email.dto.message.EmailVerificationRequestedMessage;
import com.reallive.notificationservice.email.entity.EmailNotificationStatus;
import com.reallive.notificationservice.email.repository.EmailNotificationRepository;
import com.reallive.notificationservice.email.service.EmailVerificationDeliveryService;
import com.reallive.notificationservice.email.service.impl.EmailNotificationHistory;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:email_delivery_pipeline;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true", "notification.email.consumer-enabled=true",
        "notification.email.max-retries=1", "notification.email.retry-backoff-ms=50",
        "notification.email.sendgrid.request-timeout=3s"
})
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class EmailDeliveryIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, ResponsePlan> RESPONSES = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    private static final Map<String, String> PAYLOADS = new ConcurrentHashMap<>();
    private static final ExecutorService HTTP_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer HTTP = httpServer();
    private static final String GROUP = "email-pipeline-test-" + UUID.randomUUID();

    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private EmailNotificationRepository repository;
    @Autowired private EmailDeliveryProperties properties;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private EmailVerificationDeliveryService delivery;
    @MockitoSpyBean private EmailNotificationHistory history;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("notification.email.sendgrid.base-url", () -> "http://127.0.0.1:" + HTTP.getAddress().getPort());
        registry.add("spring.kafka.consumer.group-id", () -> GROUP);
    }

    @AfterAll
    static void closeHttp() {
        RESPONSES.values().forEach(plan -> plan.release.countDown());
        HTTP.stop(0);
        HTTP_EXECUTOR.close();
    }

    @Test
    void kafkaToProviderPersistsAcceptedBeforeCommittingAndCompletedDuplicateDoesNotResend() throws Exception {
        var message = message(202);
        var plan = RESPONSES.get(message.getEventId().toString());
        plan.release = new CountDownLatch(1);
        var metadata = publish(message);
        assertTrue(plan.entered.await(10, TimeUnit.SECONDS));
        assertEquals(EmailNotificationStatus.PROCESSING, repository.findByEventId(message.getEventId()).orElseThrow().getStatus());
        assertFalse(committed(metadata));
        plan.release.countDown();
        awaitStatus(message, EmailNotificationStatus.ACCEPTED);
        await().atMost(Duration.ofSeconds(10)).until(() -> committed(metadata));
        assertEquals("012345", JSON.readTree(PAYLOADS.get(message.getEventId().toString()))
                .at("/personalizations/0/dynamic_template_data/otp").asText());
        var duplicate = publish(message);
        await().atMost(Duration.ofSeconds(10)).until(() -> committed(duplicate));
        assertEquals(1, calls(message));
        assertEquals(1, repository.findByEventId(message.getEventId()).orElseThrow().getAttemptCount());
    }

    @Test
    void retryableFailureIsRetriedAndThenAccepted() throws Exception {
        var message = message(503, 202);
        publish(message);
        awaitStatus(message, EmailNotificationStatus.ACCEPTED);
        assertEquals(2, calls(message));
        assertEquals(2, repository.findByEventId(message.getEventId()).orElseThrow().getAttemptCount());
    }

    @Test
    void permanentFailureIsRecordedAndDeadLetteredWithoutRetry() throws Exception {
        var message = message(400);
        var metadata = publish(message);
        assertEquals(JSON.writeValueAsString(message), deadLetter(JSON.writeValueAsString(message)).value());
        awaitStatus(message, EmailNotificationStatus.FAILED);
        await().atMost(Duration.ofSeconds(10)).until(() -> committed(metadata));
        assertEquals(1, calls(message));
        assertEquals(Short.valueOf((short) 400), repository.findByEventId(message.getEventId()).orElseThrow().getLastHttpStatus());
    }

    @Test
    void exhaustedRetriesAreDeadLetteredAndDurableCountStopsReplayFromCallingAgain() throws Exception {
        var message = message(503);
        publish(message);
        deadLetter(JSON.writeValueAsString(message));
        awaitStatus(message, EmailNotificationStatus.FAILED);
        assertEquals(2, calls(message));
        var replay = publish(message);
        await().atMost(Duration.ofSeconds(10)).until(() -> committed(replay));
        assertEquals(2, calls(message));
    }

    @Test
    void expiredRequestCommitsWithoutAnyProviderCall() throws Exception {
        var message = EmailDeliveryTestSupport.message(Instant.now().minusSeconds(60));
        var metadata = publish(message);
        awaitStatus(message, EmailNotificationStatus.EXPIRED);
        await().atMost(Duration.ofSeconds(10)).until(() -> committed(metadata));
        assertEquals(0, calls(message));
    }

    @Test
    void retryAfterBeyondExpiryDoesNotSendAgain() throws Exception {
        var message = message(429);
        RESPONSES.get(message.getEventId().toString()).retryAfter = "120";
        publish(message);
        awaitStatus(message, EmailNotificationStatus.EXPIRED);
        assertEquals(1, calls(message));
    }

    @Test
    void malformedMessageGoesToDltWithoutHistoryAndDoesNotLeakRawInput(CapturedOutput output) throws Exception {
        String payload = "{invalid-PRIVATE_OTP_012345";
        long before = repository.count();
        var metadata = kafka.send(properties.getTopic(), UUID.randomUUID().toString(), payload)
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
        assertEquals(payload, deadLetter(payload).value());
        await().atMost(Duration.ofSeconds(10)).until(() -> committed(metadata));
        assertEquals(before, repository.count());
        assertFalse(output.getAll().contains("PRIVATE_OTP_012345"));
    }

    @Test
    void timeoutIsUnknownAndDuplicateDoesNotResend() throws Exception {
        var message = message(202);
        var plan = RESPONSES.get(message.getEventId().toString());
        plan.release = new CountDownLatch(1);
        try {
            publish(message);
            deadLetter(JSON.writeValueAsString(message));
            awaitStatus(message, EmailNotificationStatus.UNKNOWN);
            var duplicate = publish(message);
            await().atMost(Duration.ofSeconds(10)).until(() -> committed(duplicate));
            assertEquals(1, calls(message));
        } finally {
            plan.release.countDown();
        }
    }

    @Test
    void databaseFailureAfter202DoesNotRepeatProviderCallDuringRedelivery() throws Exception {
        var message = message(202);
        doThrow(new DataAccessResourceFailureException("simulated post-provider database failure"))
                .when(history).complete(eq(message.getEventId()), anyInt(), any());
        publish(message);
        deadLetter(JSON.writeValueAsString(message));
        awaitStatus(message, EmailNotificationStatus.UNKNOWN);
        assertEquals(1, calls(message));
    }

    @Test
    void twoWorkersCannotSubmitSameEventWhileFirstHttpCallIsInFlight() throws Exception {
        var message = message(202);
        var plan = RESPONSES.get(message.getEventId().toString());
        plan.release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> delivery.deliver(message));
            try {
                assertTrue(plan.entered.await(10, TimeUnit.SECONDS));
                assertThrows(AppException.class, () -> delivery.deliver(message));
                assertEquals(1, calls(message));
            } finally {
                plan.release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
        awaitStatus(message, EmailNotificationStatus.ACCEPTED);
    }

    private EmailVerificationRequestedMessage message(int... statuses) {
        var message = EmailDeliveryTestSupport.message(Instant.now());
        RESPONSES.put(message.getEventId().toString(), new ResponsePlan(statuses));
        return message;
    }

    private RecordMetadata publish(EmailVerificationRequestedMessage message) throws Exception {
        return kafka.send(properties.getTopic(), message.getUserId().toString(), JSON.writeValueAsString(message))
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private void awaitStatus(EmailVerificationRequestedMessage message, EmailNotificationStatus expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(expected, repository.findByEventId(message.getEventId()).map(notification -> notification.getStatus()).orElse(null)));
    }

    private int calls(EmailVerificationRequestedMessage message) {
        return CALLS.getOrDefault(message.getEventId().toString(), new AtomicInteger()).get();
    }

    private boolean committed(RecordMetadata metadata) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            var offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(new TopicPartition(metadata.topic(), metadata.partition()));
            return offset != null && offset.offset() > metadata.offset();
        }
    }

    private ConsumerRecord<String, String> deadLetter(String payload) {
        var config = KafkaTestUtils.consumerProps(broker, "dlt-observer-" + UUID.randomUUID(), false);
        try (var consumer = new DefaultKafkaConsumerFactory<>(config,
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of(properties.getDeadLetterTopic()));
            Instant deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (payload.equals(record.value())) return record;
                }
            }
            throw new AssertionError("Expected dead-letter record was not observed");
        }
    }

    private static HttpServer httpServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(HTTP_EXECUTOR);
            server.createContext("/v3/mail/send", exchange -> {
                String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String eventId = JSON.readTree(payload).at("/personalizations/0/custom_args/eventId").asText();
                int attempt = CALLS.computeIfAbsent(eventId, ignored -> new AtomicInteger()).incrementAndGet();
                PAYLOADS.put(eventId, payload);
                var plan = RESPONSES.get(eventId);
                assertNotNull(plan);
                plan.entered.countDown();
                try {
                    plan.release.await(10, TimeUnit.SECONDS);
                    if (plan.retryAfter != null) exchange.getResponseHeaders().set("Retry-After", plan.retryAfter);
                    exchange.getResponseHeaders().set("X-Message-ID", "test-provider-" + eventId);
                    int status = plan.statuses[Math.min(attempt - 1, plan.statuses.length - 1)];
                    exchange.sendResponseHeaders(status, -1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to start test HTTP server", exception);
        }
    }

    private static class ResponsePlan {
        final int[] statuses;
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(0);
        volatile String retryAfter;

        ResponsePlan(int[] statuses) {
            this.statuses = statuses;
        }
    }
}
