package com.reallive.channelservice.client.user;

import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import com.reallive.channelservice.common.adapter.redis.RedisAuthoritiesReader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.cloud.openfeign.client.config.userCategoryPreferences.connectTimeout=200",
        "spring.cloud.openfeign.client.config.userCategoryPreferences.readTimeout=500"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class UserCategoryPreferenceClientIntegrationTest {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer SERVER = startServer();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static volatile int responseStatus;
    private static volatile String responseBody;
    private static volatile String requestPath;
    private static volatile String requestMethod;
    private static volatile boolean blockResponse;
    private static volatile CountDownLatch releaseResponse = new CountDownLatch(1);

    @Autowired
    private UserCategoryPreferenceResolver resolver;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private RedisAuthoritiesReader authorities;

    @DynamicPropertySource
    static void userService(DynamicPropertyRegistry registry) {
        registry.add("app.service.user", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/user");
    }

    @BeforeEach
    void resetServer() {
        responseStatus = 200;
        responseBody = envelope("{\"categoryIds\":[]}");
        blockResponse = false;
        releaseResponse = new CountDownLatch(1);
        CALLS.set(0);
        when(authorities.read(any())).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @AfterEach
    void releaseHandler() {
        releaseResponse.countDown();
    }

    @AfterAll
    static void closeServer() {
        SERVER.stop(0);
        EXECUTOR.shutdownNow();
    }

    @Test
    void usesInternalContextPathAndDeduplicatesCategories() {
        UUID user = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        responseBody = envelope("{\"categoryIds\":[\"" + category + "\",\"" + category + "\"]}");

        assertEquals(Set.of(category), resolver.getInterestCategoryIds(user));
        assertEquals("/user/internal/api/users/" + user + "/category-preferences", requestPath);
        assertEquals("GET", requestMethod);
        assertEquals(1, CALLS.get());
    }

    @Test
    void emptyListIsAValidResponse() {
        assertEquals(Set.of(), resolver.getInterestCategoryIds(UUID.randomUUID()));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void temporaryUpstreamFailuresFallBackWithoutRetrying(int status) {
        responseStatus = status;
        assertEquals(Set.of(), resolver.getInterestCategoryIds(UUID.randomUUID()));
        assertEquals(1, CALLS.get());
    }

    @Test
    void readTimeoutUsesTheDedicatedClientConfigurationAndFallsBack() {
        blockResponse = true;
        assertEquals(Set.of(), resolver.getInterestCategoryIds(UUID.randomUUID()));
        assertEquals(1, CALLS.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429})
    void clientErrorsAreNotTreatedAsEmptyPreferences(int status) {
        responseStatus = status;
        AppException exception = assertThrows(AppException.class,
                () -> resolver.getInterestCategoryIds(UUID.randomUUID()));
        assertEquals(status == 404 ? ErrorCode.USER_NOT_FOUND : ErrorCode.USER_SERVICE_UNAVAILABLE,
                exception.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{broken", "null", "{}", "{\"status\":200,\"code\":1000,\"result\":null}",
            "{\"status\":200,\"code\":9999,\"result\":{\"categoryIds\":[]}}",
            "{\"status\":500,\"code\":1000,\"result\":{\"categoryIds\":[]}}"})
    void malformedEnvelopesDoNotFallBack(String body) {
        responseBody = body;
        assertEquals(ErrorCode.USER_SERVICE_UNAVAILABLE, assertThrows(AppException.class,
                () -> resolver.getInterestCategoryIds(UUID.randomUUID())).getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"categoryIds\":null}", "{\"categoryIds\":[null]}",
            "{\"categoryIds\":[\"not-a-uuid\"]}"})
    void malformedPreferenceListsDoNotFallBack(String result) {
        responseBody = envelope(result);
        assertEquals(ErrorCode.USER_SERVICE_UNAVAILABLE, assertThrows(AppException.class,
                () -> resolver.getInterestCategoryIds(UUID.randomUUID())).getErrorCode());
    }

    @Test
    void discoveryCombinesHttpPreferencesWithDatabaseRankingAndFallback() throws Exception {
        UUID user = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID matched = insertChannel("Matched", 10);
        UUID other = insertChannel("Other", 1000);
        jdbc.update("INSERT INTO categories (id, name, slug, status) VALUES (?, ?, ?, 'ACTIVE')",
                category, category.toString(), category.toString());
        jdbc.update("INSERT INTO channel_categories (channel_id, category_id) VALUES (?, ?)", matched, category);
        responseBody = envelope("{\"categoryIds\":[\"" + category + "\"]}");
        mvc.perform(get("/api/channels").with(jwt().jwt(token -> token.subject(user.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].id").value(matched.toString()))
                .andExpect(jsonPath("$.result.content[1].id").value(other.toString()))
                .andExpect(jsonPath("$.result.totalElements").value(2));
        responseStatus = 503;
        mvc.perform(get("/api/channels").with(jwt().jwt(token -> token.subject(user.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].id").value(other.toString()))
                .andExpect(jsonPath("$.result.totalElements").value(2));
        assertEquals(2, CALLS.get());
        mvc.perform(get("/api/channels").param("keyword", "Other")
                        .with(jwt().jwt(token -> token.subject(user.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].id").value(other.toString()))
                .andExpect(jsonPath("$.result.totalElements").value(1));
        mvc.perform(get("/api/channels/following").with(jwt().jwt(token -> token.subject(user.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.totalElements").value(0));
        assertEquals(2, CALLS.get());
    }

    @Test
    void discoveryReportsMissingUsersAndMalformedUpstreamResponses() throws Exception {
        UUID user = UUID.randomUUID();
        responseStatus = 404;
        mvc.perform(get("/api/channels").with(jwt().jwt(token -> token.subject(user.toString()))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1022));
        responseStatus = 200;
        responseBody = envelope("{\"categoryIds\":null}");
        mvc.perform(get("/api/channels").with(jwt().jwt(token -> token.subject(user.toString()))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1008));
    }

    private UUID insertChannel(String name, long followers) {
        UUID channel = UUID.randomUUID();
        jdbc.update("INSERT INTO channels (id, streamer_id, name, slug, status, follower_count) VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
                channel, UUID.randomUUID(), name, channel.toString(), followers);
        return channel;
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/user/internal/api/users/", UserCategoryPreferenceClientIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start User Service stub", exception);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        CALLS.incrementAndGet();
        requestPath = exchange.getRequestURI().getPath();
        requestMethod = exchange.getRequestMethod();
        CountDownLatch release = releaseResponse;
        try (exchange) {
            if (blockResponse) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        }
    }

    private static String envelope(String result) {
        return "{\"status\":200,\"code\":1000,\"result\":" + result + "}";
    }
}
