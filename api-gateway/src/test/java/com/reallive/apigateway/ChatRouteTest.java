package com.reallive.apigateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gateway.security.csrf-enabled=false", "REDIS_PASSWORD=test-only"})
@AutoConfigureWebTestClient
@ActiveProfiles("dev")
class ChatRouteTest {
    private static final String KEY = "chat-route-test-signing-key-".repeat(4);
    private static final DisposableServer BACKEND = HttpServer.create().host("127.0.0.1").port(0)
            .route(routes -> routes.get("/chat/api/streams/test/messages", (request, response) ->
                            response.sendString(Mono.just("history")))
                    .get("/chat/api/streams/cors/messages", (request, response) -> response
                            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
                            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                            .sendString(Mono.just("history")))
                    .get("/chat/socket.io/", (request, response) -> response.sendWebsocket((in, out) ->
                            out.sendString(in.receive().asString().map(frame -> "echo:" + frame)))))
            .bindNow();
    @Autowired WebTestClient client;
    @Value("${local.server.port}") int port;
    @MockitoBean ReactiveStringRedisTemplate redis;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("gateway.security.signer-key", () -> KEY);
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-dev.yml"));
        var properties = yaml.getObject();
        String prefix = "spring.cloud.gateway.server.webflux.routes[";
        for (String property : properties.stringPropertyNames()) {
            if (!property.startsWith(prefix)) continue;
            String id = properties.getProperty(property.substring(0, property.lastIndexOf('.') + 1) + "id");
            String value = properties.getProperty(property);
            if (property.endsWith(".uri") && "chat-http".equals(id)) value = "http://127.0.0.1:" + BACKEND.port();
            if (property.endsWith(".uri") && "chat-websocket".equals(id)) value = "ws://127.0.0.1:" + BACKEND.port();
            String configured = value;
            registry.add(property, () -> configured);
        }
    }

    @BeforeEach
    void activeAccount() {
        ReactiveValueOperations<String, String> values = Mockito.mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.just("ACTIVE"));
        when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
    }
    @AfterAll
    static void close() { BACKEND.disposeNow(); }

    @Test
    void proxiesHistoryAndDoesNotExposeInternalChatOrIntegrationEndpoints() throws Exception {
        client.get().uri("/chat/api/streams/test/messages").exchange().expectStatus().isUnauthorized();
        client.get().uri("/chat/api/streams/test/messages").cookie("access_token", token())
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("history");
        for (String path : new String[]{"/chat/internal/health/ready", "/internal/health/ready",
                "/stream/internal/api/streams/test/chat-context", "/channel/internal/api/channels/test/chat-access",
                "/user/internal/api/users/test/chat-profile"}) {
            client.get().uri(path).cookie("access_token", token()).exchange().expectStatus().isNotFound();
        }
    }
    @Test
    void returnsOneCorsOriginWhenChatAndGatewayBothSetCorsHeaders() throws Exception {
        client.get().uri("/chat/api/streams/cors/messages")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .cookie("access_token", token())
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectBody(String.class).isEqualTo("history");
    }

    @Test
    void proxiesWebSocketFramesAfterCookieAuthenticatedUpgrade() throws Exception {
        String response = HttpClient.create().headers(headers -> headers.set("Cookie", "access_token=" + uncheckedToken()))
                .websocket().uri("ws://127.0.0.1:" + port + "/chat/socket.io/?EIO=4&transport=websocket")
                .handle((in, out) -> out.sendString(Mono.just("chat-frame")).then()
                        .thenMany(in.receive().asString().take(1)))
                .blockLast(Duration.ofSeconds(10));
        assertEquals("echo:chat-frame", response);
    }
    @Test
    void rejectsUnauthenticatedWebSocketHandshake() {
        client.get().uri("/chat/socket.io/?EIO=4&transport=websocket")
                .header("Upgrade", "websocket").header("Connection", "Upgrade")
                .header("Sec-WebSocket-Version", "13").header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
                .exchange().expectStatus().isUnauthorized();
    }
    private String uncheckedToken() {
        try { return token(); } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private String token() throws Exception {
        var now = Instant.now();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString()).issuer("reallive.com").jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).claim("type", "ACCESS").build());
        jwt.sign(new MACSigner(KEY.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
