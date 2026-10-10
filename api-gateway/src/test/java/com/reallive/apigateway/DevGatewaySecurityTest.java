package com.reallive.apigateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.security.signer-key=dev-test-signing-key-dev-test-signing-key-dev-test-signing-key-dev-test-")
@AutoConfigureWebTestClient
@ActiveProfiles("dev")
class DevGatewaySecurityTest {

    private static final AtomicInteger BACKEND_CALLS = new AtomicInteger();
    private static final HttpServer BACKEND = startBackend();

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "user-dev-test");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].uri",
                () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/user/**");
        registry.add("spring.cloud.gateway.server.webflux.routes[1].id", () -> "notification-dev-test");
        registry.add("spring.cloud.gateway.server.webflux.routes[1].uri",
                () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
        registry.add("spring.cloud.gateway.server.webflux.routes[1].predicates[0]", () -> "Path=/notification/**");
    }

    @BeforeEach
    void setUp() {
        BACKEND_CALLS.set(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"login", "register", "refresh", "logout"})
    void publicPostWithoutCsrfReachesBackend(String action) {
        client.post().uri("/user/api/auth/" + action).exchange().expectStatus().isOk()
                .expectCookie().doesNotExist("XSRF-TOKEN");
        assertEquals(1, BACKEND_CALLS.get());
    }

    @Test
    void invalidTokenCookieIsStillRejectedWithoutCsrf() {
        client.post().uri("/user/api/auth/login").cookie("access_token", "invalid")
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo(1001);
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void protectedPostStillRequiresAccessCookie() {
        client.post().uri("/user/api/private").exchange().expectStatus().isUnauthorized();
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void webhookIsPublicWhenDevCsrfIsDisabled() {
        client.post().uri("/notification/api/webhooks/sendgrid").exchange().expectStatus().isOk();
        assertEquals(1, BACKEND_CALLS.get());
    }

    @Test
    void otherNotificationEndpointsRemainProtectedWhenCsrfIsDisabled() {
        client.post().uri("/notification/api/webhooks/sendgrid/extra").exchange().expectStatus().isUnauthorized();
        client.post().uri("/notification/api/webhooks/sendgrid/events").exchange().expectStatus().isUnauthorized();
        client.get().uri("/notification/api/webhooks/sendgrid").exchange().expectStatus().isUnauthorized();
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void csrfEndpointIsNotRegisteredWhenDisabled() {
        client.get().uri("/api/auth/csrf").exchange().expectStatus().isNotFound();
        assertEquals(0, BACKEND_CALLS.get());
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                BACKEND_CALLS.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }
}
