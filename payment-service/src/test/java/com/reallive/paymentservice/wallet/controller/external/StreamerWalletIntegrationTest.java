package com.reallive.paymentservice.wallet.controller.external;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.paymentservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.paymentservice.wallet.client.streamer.StreamerApiClient;
import com.reallive.paymentservice.wallet.dto.response.StreamerWalletResponse;
import com.reallive.paymentservice.wallet.entity.StreamerWalletEntity;
import com.reallive.paymentservice.wallet.repository.StreamerWalletRepository;
import com.reallive.paymentservice.wallet.service.StreamerWalletService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.cloud.openfeign.client.config.activeStreamer.connectTimeout=300",
        "spring.cloud.openfeign.client.config.activeStreamer.readTimeout=300",
        "spring.cloud.openfeign.client.config.user-service.connectTimeout=300",
        "spring.cloud.openfeign.client.config.user-service.readTimeout=300"
})
@ActiveProfiles("test")
class StreamerWalletIntegrationTest {

    private static final String CONTEXT = "/payment";
    private static final String STREAMER_WALLET_PATH = CONTEXT + "/api/streamer-wallets/me";
    private static final String SCHEMA = "streamer_wallet_api_" + UUID.randomUUID().toString().replace("-", "");
    private static final ExecutorService SERVER_EXECUTOR = Executors.newCachedThreadPool();
    private static final Queue<String> USER_SERVICE_REQUESTS = new ConcurrentLinkedQueue<>();
    private static volatile int upstreamStatus = 200;
    private static volatile String upstreamBody = "";
    private static volatile boolean blockResponse = false;
    private static volatile CountDownLatch releaseResponse = new CountDownLatch(1);
    private static final HttpServer USER_SERVICE = startUserServiceStub();

    @MockitoBean
    private RedisAuthoritiesReader authAuthoritiesReader;

    @MockitoSpyBean
    private StreamerApiClient streamerApiClient;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private StreamerWalletRepository repository;

    @Autowired
    private StreamerWalletService streamerWalletService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("wallet.test.database-url",
                "jdbc:h2:mem:streamer_wallet_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username", () -> System.getProperty("wallet.test.database-username", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("wallet.test.database-password", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "\"" + SCHEMA + "\"");
        registry.add("app.service.user", () -> "http://127.0.0.1:" + USER_SERVICE.getAddress().getPort() + "/user");
    }

    @AfterAll
    static void cleanUp(@Autowired JdbcTemplate jdbc) {
        USER_SERVICE.stop(0);
        SERVER_EXECUTOR.shutdownNow();
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        USER_SERVICE_REQUESTS.clear();
        upstreamStatus = 200;
        upstreamBody = "";
        blockResponse = false;
        releaseResponse = new CountDownLatch(1);
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenReturn(List.of(new SimpleGrantedAuthority("ROLE_STREAMER")));
    }

    @AfterEach
    void releaseBlockedResponses() {
        releaseResponse.countDown();
    }

    @Test
    void anonymousRequestsReturn401WithoutCallingUserServiceOrCreatingWallet() throws Exception {
        long count = repository.count();

        mvc.perform(post(STREAMER_WALLET_PATH).contextPath(CONTEXT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value(1001));
        mvc.perform(get(STREAMER_WALLET_PATH).contextPath(CONTEXT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value(1001));

        assertTrue(USER_SERVICE_REQUESTS.isEmpty());
        assertEquals(count, repository.count());
    }

    @Test
    void malformedJwtSubjectReturns401WithoutCallingUserService() throws Exception {
        mvc.perform(post(STREAMER_WALLET_PATH).contextPath(CONTEXT)
                        .cookie(accessCookie("invalid-uuid", "ROLE_STREAMER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));

        assertTrue(USER_SERVICE_REQUESTS.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ROLE_USER", "ROLE_ADMIN", "CHANNEL_UPDATE"})
    void nonStreamerAuthoritiesReturn403WithoutCallingUserService(String authorities) throws Exception {
        long count = repository.count();
        UUID userId = UUID.randomUUID();
        Cookie cookie = accessCookie(userId.toString(), authorities);

        mvc.perform(post(STREAMER_WALLET_PATH).contextPath(CONTEXT).cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value("Access Denied"))
                .andExpect(jsonPath("$.result").value(nullValue()));

        mvc.perform(get(STREAMER_WALLET_PATH).contextPath(CONTEXT).cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value(1002));

        assertTrue(USER_SERVICE_REQUESTS.isEmpty());
        assertEquals(count, repository.count());
    }

    @Test
    void getBeforeCreateReturns404WithoutCreatingWalletAndPostCreatesIdempotentlyOutsideTx() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        replyActiveStreamer(streamerId);
        Cookie cookie = accessCookie(userId.toString(), "ROLE_USER ROLE_STREAMER");

        AtomicBoolean txActiveDuringRemoteLookup = new AtomicBoolean(false);
        doAnswer(invocation -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                txActiveDuringRemoteLookup.set(true);
            }
            return invocation.callRealMethod();
        }).when(streamerApiClient).getActiveStreamerIdByUserId(any());

        mvc.perform(get(STREAMER_WALLET_PATH).contextPath(CONTEXT).cookie(cookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value(1013))
                .andExpect(jsonPath("$.message").value("Streamer wallet not found"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        assertTrue(repository.findByStreamerId(streamerId).isEmpty());
        assertFalse(txActiveDuringRemoteLookup.get());

        mvc.perform(post(STREAMER_WALLET_PATH).contextPath(CONTEXT).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("Streamer wallet ready"))
                .andExpect(jsonPath("$.result.walletId").isNotEmpty())
                .andExpect(jsonPath("$.result.streamerId").value(streamerId.toString()))
                .andExpect(jsonPath("$.result.availableBalance").value(0));

        assertFalse(txActiveDuringRemoteLookup.get());
        StreamerWalletEntity wallet = repository.findByStreamerId(streamerId).orElseThrow();
        assertNotNull(wallet.getCreatedAt());
        assertNotNull(wallet.getUpdatedAt());

        mvc.perform(get(STREAMER_WALLET_PATH).contextPath(CONTEXT).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("Streamer wallet balance retrieved successfully"))
                .andExpect(jsonPath("$.result.walletId").value(wallet.getId().toString()))
                .andExpect(jsonPath("$.result.streamerId").value(streamerId.toString()))
                .andExpect(jsonPath("$.result.availableBalance").value(0));

        assertEquals(
                List.of(
                        "GET /user/internal/api/streamers/by-user/" + userId + "/active",
                        "GET /user/internal/api/streamers/by-user/" + userId + "/active",
                        "GET /user/internal/api/streamers/by-user/" + userId + "/active"
                ),
                List.copyOf(USER_SERVICE_REQUESTS)
        );
    }

    @Test
    void repeatPostReturnsExistingWalletAndPreservesBalanceAndTimestamps() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        replyActiveStreamer(streamerId);
        StreamerWalletEntity existing = repository.saveAndFlush(StreamerWalletEntity.builder()
                .streamerId(streamerId)
                .availableBalance(2400L)
                .build());

        mvc.perform(post(STREAMER_WALLET_PATH).contextPath(CONTEXT)
                        .cookie(accessCookie(userId.toString(), "ROLE_STREAMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.result.walletId").value(existing.getId().toString()))
                .andExpect(jsonPath("$.result.streamerId").value(streamerId.toString()))
                .andExpect(jsonPath("$.result.availableBalance").value(2400));

        StreamerWalletEntity reloaded = repository.findByStreamerId(streamerId).orElseThrow();
        assertEquals(existing.getCreatedAt(), reloaded.getCreatedAt());
        assertEquals(existing.getUpdatedAt(), reloaded.getUpdatedAt());
    }

    @ParameterizedTest
    @CsvSource({
            "404,404,1010,Streamer not found",
            "403,403,1011,Streamer is not active",
            "401,503,1012,User service unavailable",
            "500,503,1012,User service unavailable",
            "503,503,1012,User service unavailable"
    })
    void mapsUpstreamHttpFailuresOnPostAndGetWithoutCreatingWallet(
            int upstreamHttpStatus, int expectedStatus, int expectedCode, String expectedMessage) throws Exception {
        long count = repository.count();
        UUID userId = UUID.randomUUID();
        Cookie cookie = accessCookie(userId.toString(), "ROLE_STREAMER");
        upstreamStatus = upstreamHttpStatus;
        upstreamBody = "{\"status\":" + upstreamHttpStatus + ",\"code\":9999,\"result\":null}";

        mvc.perform(post(STREAMER_WALLET_PATH).contextPath(CONTEXT).cookie(cookie))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.message").value(expectedMessage))
                .andExpect(jsonPath("$.result").value(nullValue()));

        mvc.perform(get(STREAMER_WALLET_PATH).contextPath(CONTEXT).cookie(cookie))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.message").value(expectedMessage));

        assertEquals(count, repository.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not-json",
            "{}",
            "{\"status\":200,\"code\":1000,\"result\":null}",
            "{\"status\":200,\"code\":1000,\"result\":{}}",
            "{\"status\":200,\"code\":1000,\"result\":{\"streamerId\":\"invalid-uuid\"}}",
            "{\"status\":500,\"code\":1000,\"result\":{\"streamerId\":\"00000000-0000-0000-0000-000000000001\"}}",
            "{\"status\":200,\"code\":1001,\"result\":{\"streamerId\":\"00000000-0000-0000-0000-000000000001\"}}"
    })
    void malformedUpstream200BodiesReturn503WithoutCreatingWallet(String body) throws Exception {
        long count = repository.count();
        UUID userId = UUID.randomUUID();
        upstreamStatus = 200;
        upstreamBody = body;

        mvc.perform(post(STREAMER_WALLET_PATH).contextPath(CONTEXT)
                        .cookie(accessCookie(userId.toString(), "ROLE_STREAMER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value(1012))
                .andExpect(jsonPath("$.message").value("User service unavailable"));

        assertEquals(count, repository.count());
    }

    @Test
    void upstreamReadTimeoutReturns503WithoutCreatingWallet() throws Exception {
        long count = repository.count();
        UUID userId = UUID.randomUUID();
        replyActiveStreamer(UUID.randomUUID());
        blockResponse = true;

        mvc.perform(post(STREAMER_WALLET_PATH).contextPath(CONTEXT)
                        .cookie(accessCookie(userId.toString(), "ROLE_STREAMER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value(1012));

        assertEquals(count, repository.count());
    }

    @Test
    void concurrentPostRequestsReturnSameWalletId() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        replyActiveStreamer(streamerId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createConcurrently(userId, ready, start));
            var second = executor.submit(() -> createConcurrently(userId, ready, start));
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
        }

        assertEquals(1, repository.findAll().stream()
                .filter(wallet -> streamerId.equals(wallet.getStreamerId()))
                .count());
    }

    @Test
    void ensureStreamerWalletCreatesAndReusesWalletInsideCallerTransaction() {
        UUID streamerId = UUID.randomUUID();

        StreamerWalletResponse first = new TransactionTemplate(transactionManager)
                .execute(status -> streamerWalletService.ensureStreamerWallet(streamerId));
        assertNotNull(first);
        assertEquals(streamerId, first.streamerId());
        assertEquals(0L, first.availableBalance());

        StreamerWalletResponse second = streamerWalletService.ensureStreamerWallet(streamerId);
        assertEquals(first.walletId(), second.walletId());
    }

    private String createConcurrently(UUID userId, CountDownLatch ready, CountDownLatch start) throws Exception {
        Cookie cookie = accessCookie(userId.toString(), "ROLE_STREAMER");
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out starting concurrent streamer wallet requests");
        }
        String response = mvc.perform(post(STREAMER_WALLET_PATH).contextPath(CONTEXT).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.walletId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonMapper.shared().readTree(response).path("result").path("walletId").asString();
    }

    private Cookie accessCookie(String subject, String scope) throws Exception {
        List<GrantedAuthority> authorities = Arrays.stream(scope.split(" "))
                .filter(value -> !value.isBlank())
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any())).thenReturn(authorities);
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("scope", scope)
                .build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        token.sign(new MACSigner("streamer-wallet-api-test-key-".repeat(3)));
        return new Cookie("access_token", token.serialize());
    }

    private static void replyActiveStreamer(UUID streamerId) {
        upstreamStatus = 200;
        upstreamBody = "{\"status\":200,\"code\":1000,\"result\":{\"streamerId\":\"" + streamerId + "\"}}";
    }

    private static HttpServer startUserServiceStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(SERVER_EXECUTOR);
            server.createContext("/user/internal/api/streamers/by-user/", StreamerWalletIntegrationTest::handleExchange);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start User Service stub server", exception);
        }
    }

    private static void handleExchange(HttpExchange exchange) throws IOException {
        USER_SERVICE_REQUESTS.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        int status = "GET".equals(exchange.getRequestMethod()) ? upstreamStatus : 405;
        String body = upstreamBody;
        boolean shouldBlock = blockResponse;
        CountDownLatch release = releaseResponse;
        try (exchange) {
            if (shouldBlock) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        }
    }
}
