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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.security.csrf-enabled=true")
@AutoConfigureWebTestClient(timeout = "PT30S")
@ActiveProfiles({"dev", "test"})
class GatewayAuthenticationTest {

    static final String SIGNING_KEY = "gateway-test-secret-".repeat(4);
    private static final String SUBJECT = UUID.randomUUID().toString();
    private static final String AUTH_PATH = "/user/api/auth";
    private static final AtomicInteger BACKEND_CALLS = new AtomicInteger();
    private static final AtomicReference<CapturedRequest> LAST_REQUEST = new AtomicReference<>();
    private static final HttpServer BACKEND = startBackend();

    @Autowired
    private WebTestClient client;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    private final Set<String> revoked = ConcurrentHashMap.newKeySet();
    private volatile boolean redisUnavailable;
    private CsrfCredentials csrf;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("gateway.security.signer-key", () -> SIGNING_KEY);
        registry.add("gateway.security.blacklist-timeout", () -> "50ms");
        for (int index = 0; index < 3; index++) {
            String prefix = "spring.cloud.gateway.server.webflux.routes[" + index + "]";
            String service = List.of("user", "channel", "notification").get(index);
            registry.add(prefix + ".id", () -> service + "-test");
            registry.add(prefix + ".uri", () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
            registry.add(prefix + ".predicates[0]", () -> "Path=/" + service + "/**");
        }
    }

    @BeforeEach
    void setUp() {
        BACKEND_CALLS.set(0);
        LAST_REQUEST.set(null);
        revoked.clear();
        redisUnavailable = false;
        when(redisTemplate.hasKey(anyString())).thenAnswer(invocation -> redisUnavailable
                ? Mono.error(new IOException("Redis offline"))
                : Mono.just(revoked.contains(invocation.getArgument(0))));
        var response = client.get().uri("/api/auth/csrf").exchange()
                .expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody().returnResult();
        var body = objectMapper.readTree(response.getResponseBody()).path("result");
        csrf = new CsrfCredentials(response.getResponseCookies().getFirst("XSRF-TOKEN").getValue(),
                body.path("headerName").asString(), body.path("token").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"login", "register", "refresh", "logout", "forgot-password", "verify-reset-otp", "reset-password"})
    void publicPostWithoutTokensReachesBackend(String action) {
        request(HttpMethod.POST, AUTH_PATH + "/" + action).exchange().expectStatus().isOk();
        assertEquals(1, BACKEND_CALLS.get());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void webhookWithoutCookiesBypassesCsrfAndPreservesSignatureInputs() {
        String body = "[ {\"event\":\"delivered\",\"reason\":\"Tiếng Việt\"} ]\r\n";
        client.post().uri("/notification/api/webhooks/sendgrid")
                .header("X-Twilio-Email-Event-Webhook-Signature", "provider-signature")
                .header("X-Twilio-Email-Event-Webhook-Timestamp", "1700000000")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();
        assertEquals(1, BACKEND_CALLS.get());
        assertEquals("/notification/api/webhooks/sendgrid", LAST_REQUEST.get().path());
        assertEquals(body, LAST_REQUEST.get().body());
        assertEquals("provider-signature", LAST_REQUEST.get().signature());
        assertEquals("1700000000", LAST_REQUEST.get().timestamp());
        verifyNoInteractions(redisTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/notification/api/webhooks/sendgrid/extra",
            "/notification/api/webhooks/sendgrid/events", "/notification/api/private"})
    void otherNotificationPathsStillRequireAuthenticationAndCsrf(String path) {
        client.post().uri(path).exchange().expectStatus().isForbidden();
        request(HttpMethod.POST, path).exchange().expectStatus().isUnauthorized();
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void otherWebhookMethodsAreNotPublic() {
        client.get().uri("/notification/api/webhooks/sendgrid").exchange().expectStatus().isUnauthorized();
        client.put().uri("/notification/api/webhooks/sendgrid").exchange().expectStatus().isForbidden();
        request(HttpMethod.PUT, "/notification/api/webhooks/sendgrid").exchange().expectStatus().isUnauthorized();
        assertEquals(0, BACKEND_CALLS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"email-verification/otp", "email-verification/verify", "become-streamer"})
    void userRegistrationApisRemainProtected(String action) {
        String path = "/user/api/users/me/" + action;
        client.post().uri(path).exchange().expectStatus().isForbidden();
        request(HttpMethod.POST, path).exchange().expectStatus().isUnauthorized();
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void loginPreservesPathBodyAndBothSetCookieHeaders() {
        request(HttpMethod.POST, AUTH_PATH + "/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"tester\",\"password\":\"password\"}")
                .exchange().expectStatus().isOk()
                .expectHeader().values(HttpHeaders.SET_COOKIE, values -> {
                    assertTrue(values.stream().anyMatch(value -> value.startsWith("access_token=")));
                    assertTrue(values.stream().anyMatch(value -> value.startsWith("refresh_token=")));
                });
        assertEquals(AUTH_PATH + "/login", LAST_REQUEST.get().path());
        assertTrue(LAST_REQUEST.get().body().contains("tester"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"login", "register", "refresh", "logout", "forgot-password", "verify-reset-otp", "reset-password"})
    void invalidCookieIsRejectedEvenOnPublicEndpoints(String action) {
        request(HttpMethod.POST, AUTH_PATH + "/" + action).cookie("access_token", "malformed")
                .exchange().expectStatus().isUnauthorized()
                .expectCookie().maxAge("access_token", Duration.ZERO).expectCookie().path("access_token", "/")
                .expectBody().jsonPath("$.code").isEqualTo(1001);
        assertEquals(0, BACKEND_CALLS.get());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void verifiesBothCookiesBeforeForwarding() throws Exception {
        Token access = token("ACCESS");
        Token refresh = token("REFRESH");
        request(HttpMethod.GET, "/channel/api/private")
                .cookie("access_token", access.value()).cookie("refresh_token", refresh.value())
                .exchange().expectStatus().isOk();
        verify(redisTemplate).hasKey("auth:revoked:access:" + access.jti());
        verify(redisTemplate).hasKey("auth:revoked:refresh:" + refresh.jti());
        assertTrue(LAST_REQUEST.get().cookies().contains("access_token=" + access.value()));
        assertNull(LAST_REQUEST.get().authorization());
    }

    @Test
    void validAccessDoesNotHideInvalidRefreshCookie() throws Exception {
        request(HttpMethod.GET, "/channel/api/private").cookie("access_token", token("ACCESS").value())
                .cookie("refresh_token", "invalid")
                .exchange().expectStatus().isUnauthorized()
                .expectCookie().maxAge("refresh_token", Duration.ZERO).expectCookie().path("refresh_token", AUTH_PATH)
                .expectCookie().doesNotExist("access_token");
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void clearsAllInvalidCookiesInOneResponse() {
        request(HttpMethod.POST, AUTH_PATH + "/login")
                .cookie("access_token", "bad-access").cookie("refresh_token", "bad-refresh")
                .exchange().expectStatus().isUnauthorized()
                .expectCookie().maxAge("access_token", Duration.ZERO).expectCookie().maxAge("refresh_token", Duration.ZERO);
    }

    @Test
    void refreshOnlyCookieCanRefreshButCannotAccessBusinessApi() throws Exception {
        Token refresh = token("REFRESH");
        request(HttpMethod.POST, AUTH_PATH + "/refresh").cookie("refresh_token", refresh.value())
                .exchange().expectStatus().isOk();
        request(HttpMethod.GET, "/channel/api/private").cookie("refresh_token", refresh.value())
                .exchange().expectStatus().isUnauthorized();
        assertEquals(1, BACKEND_CALLS.get());
    }

    @Test
    void refreshWithoutRefreshCookieIsForwardedWithVerifiedAccess() throws Exception {
        Token access = token("ACCESS");
        request(HttpMethod.POST, AUTH_PATH + "/refresh").cookie("access_token", access.value())
                .exchange().expectStatus().isOk();
        assertEquals(1, BACKEND_CALLS.get());
        verify(redisTemplate).hasKey("auth:revoked:access:" + access.jti());
    }

    @Test
    void expiredAccessBlocksRefreshDespiteValidRefresh() throws Exception {
        request(HttpMethod.POST, AUTH_PATH + "/refresh")
                .cookie("access_token", token("ACCESS", SUBJECT, "reallive.com", Instant.now().minusSeconds(1),
                        Map.of(), SIGNING_KEY, JWSAlgorithm.HS512).value())
                .cookie("refresh_token", token("REFRESH").value())
                .exchange().expectStatus().isUnauthorized().expectCookie().maxAge("access_token", Duration.ZERO);
        assertEquals(0, BACKEND_CALLS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"access", "refresh"})
    void revokedTokenIsRejectedAndCookieCleared(String kind) throws Exception {
        Token token = token(kind.toUpperCase());
        revoked.add("auth:revoked:" + kind + ":" + token.jti());
        request(HttpMethod.POST, AUTH_PATH + "/logout").cookie(kind + "_token", token.value())
                .exchange().expectStatus().isUnauthorized().expectCookie().maxAge(kind + "_token", Duration.ZERO);
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void rejectsMixedSubjects() throws Exception {
        request(HttpMethod.POST, AUTH_PATH + "/login").cookie("access_token", token("ACCESS").value())
                .cookie("refresh_token", token("REFRESH", UUID.randomUUID().toString(), "reallive.com",
                        Instant.now().plusSeconds(300), Map.of(), SIGNING_KEY, JWSAlgorithm.HS512).value())
                .exchange().expectStatus().isUnauthorized()
                .expectCookie().maxAge("access_token", Duration.ZERO).expectCookie().maxAge("refresh_token", Duration.ZERO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "issuer", "signature", "algorithm", "sub", "nbf", "iat", "scope", "exp", "jti"})
    void invalidJwtContractIsRejectedBeforeRedis(String failure) throws Exception {
        String type = failure.equals("type") ? "REFRESH" : "ACCESS";
        String subject = failure.equals("sub") ? "not-a-uuid" : SUBJECT;
        String issuer = failure.equals("issuer") ? "other.com" : "reallive.com";
        String key = failure.equals("signature") ? "different-key-".repeat(6) : SIGNING_KEY;
        JWSAlgorithm algorithm = failure.equals("algorithm") ? JWSAlgorithm.HS256 : JWSAlgorithm.HS512;
        Map<String, Object> overrides = switch (failure) {
            case "nbf" -> Map.of("nbf", Date.from(Instant.now().plusSeconds(30)));
            case "iat" -> Map.of("iat", Date.from(Instant.now().plusSeconds(30)));
            case "scope" -> Map.of("scope", 123);
            case "jti" -> Map.of("jti", "");
            default -> Map.of();
        };
        Instant expiration = failure.equals("exp") ? null : Instant.now().plusSeconds(300);
        Token invalid = token(type, subject, issuer, expiration, overrides, key, algorithm);
        request(HttpMethod.GET, "/channel/api/private").cookie("access_token", invalid.value())
                .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(redisTemplate);
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void rejectsDuplicateAndBlankAuthCookies() throws Exception {
        request(HttpMethod.GET, "/channel/api/private")
                .cookie("access_token", token("ACCESS").value()).cookie("access_token", token("ACCESS").value())
                .exchange().expectStatus().isUnauthorized();
        request(HttpMethod.POST, AUTH_PATH + "/login").cookie("access_token", "")
                .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void authorizationHeaderIsUnsupportedEvenAlongsideValidCookies() throws Exception {
        request(HttpMethod.POST, AUTH_PATH + "/login")
                .header(HttpHeaders.AUTHORIZATION, "Bearer forged")
                .cookie("access_token", token("ACCESS").value())
                .exchange().expectStatus().isBadRequest().expectCookie().doesNotExist("access_token")
                .expectBody().jsonPath("$.code").isEqualTo(1012);
        verifyNoInteractions(redisTemplate);
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void redisFailureAndTimeoutDoNotForwardOrClearCookies() throws Exception {
        redisUnavailable = true;
        request(HttpMethod.GET, "/channel/api/private").cookie("access_token", token("ACCESS").value())
                .exchange().expectStatus().isEqualTo(503).expectCookie().doesNotExist("access_token")
                .expectBody().jsonPath("$.code").isEqualTo(1013);
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.never());
        request(HttpMethod.GET, "/channel/api/private").cookie("access_token", token("ACCESS").value())
                .exchange().expectStatus().isEqualTo(503).expectCookie().doesNotExist("access_token");
        assertEquals(0, BACKEND_CALLS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"login", "register", "refresh", "logout", "forgot-password", "verify-reset-otp", "reset-password"})
    void publicPathWithWrongHttpMethodIsProtected(String action) {
        request(HttpMethod.GET, AUTH_PATH + "/" + action).exchange().expectStatus().isUnauthorized();
        request(HttpMethod.GET, "/channel/api/private").exchange().expectStatus().isUnauthorized();
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void csrfIsRequiredForUnsafeRequestsAndBootstrapAlsoChecksJwt() {
        client.post().uri(AUTH_PATH + "/login").exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo(1002);
        client.get().uri("/api/auth/csrf").cookie("access_token", "bad")
                .exchange().expectStatus().isUnauthorized().expectCookie().maxAge("access_token", Duration.ZERO);
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void corsPreflightDoesNotRequireCredentialsAndUnknownOriginIsRejected() {
        client.options().uri(AUTH_PATH + "/login")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-xsrf-token")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        client.get().uri("/api/auth/csrf").header(HttpHeaders.ORIGIN, "https://unknown.example")
                .exchange().expectStatus().isForbidden();
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void actuatorIsNotPublic() {
        client.get().uri("/actuator/health").exchange().expectStatus().isUnauthorized()
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
                .expectBody().jsonPath("$.code").isEqualTo(1001);
    }

    private WebTestClient.RequestBodySpec request(HttpMethod method, String path) {
        return client.method(method).uri(path).cookie("XSRF-TOKEN", csrf.cookie())
                .header(csrf.headerName(), csrf.token());
    }

    private Token token(String type) throws Exception {
        return token(type, SUBJECT, "reallive.com", Instant.now().plusSeconds(300),
                Map.of(), SIGNING_KEY, JWSAlgorithm.HS512);
    }

    private Token token(String type, String subject, String issuer, Instant expiresAt,
                        Map<String, Object> overrides, String key, JWSAlgorithm algorithm) throws Exception {
        String jti = UUID.randomUUID().toString();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(subject).issuer(issuer).jwtID(jti)
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(expiresAt == null ? null : Date.from(expiresAt))
                .claim("type", type).claim("scope", "ROLE_USER USER_READ");
        overrides.forEach(claims::claim);
        SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims.build());
        jwt.sign(new MACSigner(key.getBytes(StandardCharsets.UTF_8)));
        return new Token(jwt.serialize(), jti);
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                BACKEND_CALLS.incrementAndGet();
                LAST_REQUEST.set(new CapturedRequest(exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Cookie"),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                        exchange.getRequestHeaders().getFirst("X-Twilio-Email-Event-Webhook-Signature"),
                        exchange.getRequestHeaders().getFirst("X-Twilio-Email-Event-Webhook-Timestamp")));
                exchange.getResponseHeaders().add("Set-Cookie", "access_token=new-access; Path=/; HttpOnly");
                exchange.getResponseHeaders().add("Set-Cookie", "refresh_token=new-refresh; Path=" + AUTH_PATH + "; HttpOnly");
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

    private record CsrfCredentials(String cookie, String headerName, String token) {
    }

    private record Token(String value, String jti) {
    }

    private record CapturedRequest(String path, String cookies, String authorization, String body,
                                   String signature, String timestamp) {
    }
}
