package com.reallive.apigateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gateway.security.csrf-enabled=false", "REDIS_PASSWORD=test-only"})
@AutoConfigureWebTestClient
@ActiveProfiles("dev")
class PaymentInternalRouteTest {

    private static final String SIGNING_KEY = "payment-route-test-secret-".repeat(4);
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer PAYMENT = startPayment();
    @Autowired
    private WebTestClient client;
    @MockitoBean
    private ReactiveStringRedisTemplate redis;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.security.signer-key", () -> SIGNING_KEY);
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-dev.yml"));
        Properties configuration = yaml.getObject();
        String routesPrefix = "spring.cloud.gateway.server.webflux.routes[";
        String paymentUri = configuration.stringPropertyNames().stream()
                .filter(key -> key.startsWith(routesPrefix) && key.endsWith(".id"))
                .filter(key -> "payment-service".equals(configuration.getProperty(key)))
                .map(key -> key.substring(0, key.length() - 2) + "uri").findFirst().orElseThrow();
        configuration.stringPropertyNames().stream().filter(key -> key.startsWith(routesPrefix))
                .forEach(key -> registry.add(key, () -> key.equals(paymentUri)
                        ? "http://127.0.0.1:" + PAYMENT.getAddress().getPort() : configuration.getProperty(key)));
    }

    @BeforeEach
    void reset() {
        CALLS.set(0);
        ReactiveValueOperations<String, String> values = Mockito.mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.just("ACTIVE"));
        when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
    }

    @AfterAll
    static void closePayment() {
        PAYMENT.stop(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void internalWalletCreationIsNeverRoutedEvenWithValidAccessCookie(boolean authenticated) throws Exception {
        var request = client.post().uri("/payment/internal/api/wallets/create/" + UUID.randomUUID());
        if (authenticated) {
            request.cookie("access_token", accessToken());
        }
        request.exchange().expectStatus().isNotFound();
        assertEquals(0, CALLS.get());
    }

    @Test
    void configuredPublicPaymentPrefixStillRoutesAuthenticatedRequests() throws Exception {
        client.post().uri("/payment/api/balance").cookie("access_token", accessToken())
                .exchange().expectStatus().isOk();
        assertEquals(1, CALLS.get());
    }

    @Test
    void publicPaymentPrefixStillRequiresAccessCookie() {
        client.post().uri("/payment/api/balance").exchange().expectStatus().isUnauthorized();
        assertEquals(0, CALLS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/payment/wallets/create/", "/payment/api-internal/wallets/create/"})
    void legacyAndSimilarPrefixesDoNotAccidentallyExposeWalletCreation(String prefix) throws Exception {
        client.post().uri(prefix + UUID.randomUUID()).cookie("access_token", accessToken())
                .exchange().expectStatus().isNotFound();
        assertEquals(0, CALLS.get());
    }

    private String accessToken() throws Exception {
        Instant now = Instant.now();
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString()).issuer("reallive.com").jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("type", "ACCESS").claim("scope", "ROLE_USER").build());
        token.sign(new MACSigner(SIGNING_KEY.getBytes(StandardCharsets.UTF_8)));
        return token.serialize();
    }

    private static HttpServer startPayment() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                CALLS.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start route test backend", exception);
        }
    }
}
