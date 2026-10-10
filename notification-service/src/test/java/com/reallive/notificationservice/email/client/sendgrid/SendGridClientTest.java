package com.reallive.notificationservice.email.client.sendgrid;

import com.reallive.notificationservice.email.EmailDeliveryTestSupport;
import com.reallive.notificationservice.email.client.sendgrid.dto.request.SendGridMailRequest;
import com.reallive.notificationservice.email.configuration.EmailDeliveryProperties;
import com.reallive.notificationservice.email.configuration.EmailSendingConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SendGridClientTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestJson = new AtomicReference<>();
    private HttpServer server;
    private ExecutorService executor;
    private EmailDeliveryProperties properties;
    private SendGridClient client;
    private SendGridMailRequest request;
    private int status = 202;
    private String body = "";
    private String messageId;
    private String retryAfter;
    private CountDownLatch gate = new CountDownLatch(0);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/v3/mail/send", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestJson.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                gate.await(3, TimeUnit.SECONDS);
                if (messageId != null) exchange.getResponseHeaders().set("X-Message-ID", messageId);
                if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
                if (status == 302) exchange.getResponseHeaders().set("Location", "/redirected");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/redirected", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        properties = EmailDeliveryTestSupport.properties();
        request = SendGridMailRequest.builder().templateId("d-prepared-template").build();
        properties.getSendgrid().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = client();
    }

    @AfterEach
    void tearDown() {
        gate.countDown();
        server.stop(0);
        executor.close();
    }

    @Test
    void sendsPreparedDtoWithBearerAuthAndReturnsEmpty202WithMessageId() {
        messageId = "provider-submission-id";
        var result = send();
        assertEquals(202, result.getStatusCode().value());
        assertEquals(messageId, result.getHeaders().getFirst("X-Message-ID"));
        assertEquals("Bearer test-sendgrid-key", authorization.get());
        var json = mapper.readTree(requestJson.get());
        assertEquals("d-prepared-template", json.get("template_id").asText());
        assertEquals(mapper.valueToTree(request), json);
        assertEquals(1, calls.get());
    }

    @Test
    void missingMessageIdStillReturns202() {
        var result = send();
        assertEquals(202, result.getStatusCode().value());
        assertNull(result.getHeaders().getFirst("X-Message-ID"));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405, 413, 422, 302})
    void returnsErrorResponsesAndDoesNotFollowRedirects(int httpStatus) {
        status = httpStatus;
        body = "{\"errors\":[{\"message\":\"private-012345-test-sendgrid-key\"}]}";
        var result = send();
        assertEquals(status, result.getStatusCode().value());
        assertFalse(result.toString().contains("012345"));
        assertFalse(result.toString().contains("test-sendgrid-key"));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void returnsStatusAndHeadersEvenWithMalformedBody(int httpStatus) {
        status = httpStatus;
        body = "not-json-012345";
        retryAfter = "20";
        var result = send();
        assertEquals("20", result.getHeaders().getFirst("Retry-After"));
        assertEquals(status, result.getStatusCode().value());
        assertEquals(1, calls.get());
    }

    @Test
    void returnsRetryAfterHeaderWithoutInterpretingIt() {
        status = 429;
        retryAfter = "Sat, 10 Oct 2026 01:00:20 GMT";
        assertEquals(retryAfter, send().getHeaders().getFirst("Retry-After"));
    }

    @Test
    void callerControlsTimeoutWithoutAutomaticRetry() {
        gate = new CountDownLatch(1);
        assertThrows(IllegalStateException.class, () -> client.send(request, ClientResponse::toBodilessEntity)
                .block(Duration.ofSeconds(1)));
        assertEquals(1, calls.get());
    }

    @Test
    void propagatesConnectionRefusalToCaller() {
        server.stop(0);
        assertThrows(WebClientRequestException.class, this::send);
        assertEquals(0, calls.get());
    }

    private SendGridClient client() {
        return new SendGridClient(new EmailSendingConfig().sendGridWebClient(properties), properties);
    }

    private ResponseEntity<Void> send() {
        return client.send(request, ClientResponse::toBodilessEntity).block(Duration.ofSeconds(5));
    }
}
