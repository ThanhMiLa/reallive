package com.reallive.streamservice.stream.client;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.MediaTestSupport;
import com.reallive.streamservice.stream.service.StreamEligibilityService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class StreamClientsHttpTest {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID STREAMER = UUID.randomUUID();
    private static final UUID CHANNEL = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CALLS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger USER_STATUS = new AtomicInteger(200);
    private static volatile boolean malformed;
    private static volatile boolean wrongOwner;
    private static volatile boolean invalidCategory;
    private static volatile boolean delayed;
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final HttpServer SERVER = server();

    @Autowired private StreamEligibilityService eligibility;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MediaTestSupport.register(registry);
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("app.service.user", () -> base + "/user");
        registry.add("app.service.channel", () -> base + "/channel");
        registry.add("spring.cloud.openfeign.client.config.user-service.readTimeout", () -> 200);
        registry.add("spring.cloud.openfeign.client.config.channel-service.readTimeout", () -> 200);
    }

    @BeforeEach
    void reset() {
        CALLS.clear(); USER_STATUS.set(200); malformed = false; wrongOwner = false; invalidCategory = false; delayed = false;
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0); EXECUTOR.shutdownNow();
    }

    @Test
    void resolvesIdentityChannelAndCategoryThroughExactInternalHttpContracts() {
        UUID category = UUID.randomUUID();
        var context = eligibility.requirePublishContext(USER, category);
        assertEquals(USER, context.ownerUserId());
        assertEquals(STREAMER, context.streamerId());
        assertEquals(CHANNEL, context.channelId());
        assertEquals(category, context.categoryId());
        assertEquals(3, CALLS.size());
        assertTrue(CALLS.getFirst().endsWith("/by-user/" + USER + "/streaming-eligibility"));
        assertTrue(CALLS.get(1).endsWith("/by-streamer/" + STREAMER + "/streaming-context"));
        assertTrue(CALLS.get(2).endsWith("/categories/validate"));
    }

    @Test
    void missingPrincipalAndCategoryDoNotCallRemoteServices() {
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(AppException.class, () -> eligibility.requirePublishContext(null, UUID.randomUUID())).getErrorCode());
        assertEquals(ErrorCode.STREAM_REQUEST_INVALID, assertThrows(AppException.class, () -> eligibility.requirePublishContext(USER, null)).getErrorCode());
        assertTrue(CALLS.isEmpty());
    }

    @Test
    void distinguishesIneligibilityFromUpstreamFailure() {
        for (int status : new int[]{403, 404, 500, 401}) {
            USER_STATUS.set(status);
            var error = assertThrows(AppException.class, () -> eligibility.requirePublishContext(USER, UUID.randomUUID()));
            assertEquals(status == 403 || status == 404 ? ErrorCode.STREAMER_NOT_ELIGIBLE : ErrorCode.STREAM_DATA_UNAVAILABLE, error.getErrorCode());
        }
        assertEquals(4, CALLS.size());
    }

    @Test
    void malformedResponseWrongOwnerAndInvalidCategoryFailClosed() {
        malformed = true;
        assertEquals(ErrorCode.STREAM_DATA_UNAVAILABLE, assertThrows(AppException.class, () -> eligibility.requirePublishContext(USER, UUID.randomUUID())).getErrorCode());
        malformed = false; wrongOwner = true;
        assertEquals(ErrorCode.STREAM_CHANNEL_NOT_ELIGIBLE, assertThrows(AppException.class, () -> eligibility.requirePublishContext(USER, UUID.randomUUID())).getErrorCode());
        wrongOwner = false; invalidCategory = true;
        assertEquals(ErrorCode.STREAM_CATEGORY_INVALID, assertThrows(AppException.class, () -> eligibility.requirePublishContext(USER, UUID.randomUUID())).getErrorCode());
    }

    @Test
    void timesOutWithoutRetryingOrCallingChannel() {
        delayed = true;
        assertEquals(ErrorCode.STREAM_DATA_UNAVAILABLE, assertThrows(AppException.class, () -> eligibility.requirePublishContext(USER, UUID.randomUUID())).getErrorCode());
        assertEquals(1, CALLS.size());
    }

    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                CALLS.add(path);
                Object result;
                int status = 200;
                if (path.endsWith("/streaming-eligibility")) {
                    if (delayed) { LockSupport.parkNanos(1_000_000_000L); }
                    status = USER_STATUS.get();
                    result = malformed ? Map.of("unexpected", true) : Map.of("userId", USER, "streamerId", STREAMER);
                } else if (path.endsWith("/streaming-context")) {
                    result = Map.of("channelId", CHANNEL, "streamerId", STREAMER, "ownerUserId", wrongOwner ? UUID.randomUUID() : USER, "status", "ACTIVE");
                } else if (path.endsWith("/categories/validate")) {
                    result = !invalidCategory;
                } else {
                    result = Map.of(); status = 404;
                }
                byte[] body = JSON.writeValueAsBytes(Map.of("status", status, "code", status == 200 ? 1000 : 9999, "result", result));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
