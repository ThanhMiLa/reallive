package com.reallive.streamservice.stream.adapter.media;

import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.common.configuration.WebClientConfig;
import com.reallive.streamservice.common.configuration.MediaControlProperties;
import com.reallive.streamservice.common.configuration.MediaKeyMaterial;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.MediaTestSupport;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class MediaMtxAdapterTest {

    private final UUID session = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService executor;
    private MediaMtxAdapter adapter;
    private Function<Call, Reply> respond;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            var call = new Call(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"), new String(exchange.getRequestBody().readAllBytes()));
            calls.add(call);
            Reply reply = respond.apply(call);
            if (reply.delay()) {
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(reply.status(), body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        var properties = new MediaControlProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setRequestTimeout(Duration.ofSeconds(2));
        var keys = new MediaKeyMaterial((RSAPrivateKey) MediaTestSupport.KEYS.getPrivate(),
                (RSAPublicKey) MediaTestSupport.KEYS.getPublic());
        var issuer = new MediaJwtIssuer(keys, MediaTestSupport.properties(), Clock.systemUTC());
        adapter = new MediaMtxAdapter(new WebClientConfig().mediaControlWebClient(properties), properties, issuer, mapper);
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void ensureAddsExactPathAndUsesOnlyBackendApiPermission() throws Exception {
        respond = call -> {
            if (calls.size() == 1) {
                return error(404, "path configuration not found");
            }
            return call.method().equals("POST") ? new Reply(200, "{\"status\":\"ok\"}", false) : config(false);
        };
        adapter.ensurePath(session);
        assertEquals(List.of("GET", "POST", "GET"), calls.stream().map(Call::method).toList());
        assertEquals("/v3/config/paths/add/live/" + session, calls.get(1).uri());
        assertFalse(mapper.readTree(calls.get(1).body()).path("overridePublisher").asBoolean());
        for (Call call : calls) {
            var claims = SignedJWT.parse(call.authorization().substring(7)).getJWTClaimsSet();
            assertEquals(List.of(Map.of("action", "api")), claims.getClaim("mediamtx_permissions"));
        }
    }

    @Test
    void ensureNeverReplacesAnExistingLivePath() {
        respond = call -> config(false);
        adapter.ensurePath(session);
        adapter.ensurePath(session);
        assertEquals(2, calls.size());
        assertTrue(calls.stream().allMatch(call -> call.method().equals("GET")));
        calls.clear();
        respond = call -> config(true);
        unavailable(() -> adapter.ensurePath(session));
        assertEquals(1, calls.size());
    }

    @Test
    void concurrentAddConflictRechecksWithoutReplacing() {
        respond = call -> switch (calls.size()) {
            case 1 -> error(404, "path configuration not found");
            case 2 -> error(400, "path already exists");
            default -> config(false);
        };
        adapter.ensurePath(session);
        assertEquals(3, calls.size());
        assertTrue(calls.stream().noneMatch(call -> call.uri().contains("replace")));
    }

    @Test
    void readsSourceConnectionAndPaginatesBothLists() {
        UUID source = UUID.randomUUID();
        String sourceBody = "{\"name\":\"live/" + session + "\",\"online\":true,\"source\":{\"id\":\""
                + source + "\",\"type\":\"rtmpsConn\"}}";
        respond = call -> new Reply(200, sourceBody, false);
        var observed = adapter.getSource(session).orElseThrow();
        assertTrue(observed.online());
        assertEquals(source, observed.sourceId());
        assertEquals(MediaSourceType.RTMPS_CONN, observed.sourceType());
        respond = call -> new Reply(200, "{\"itemCount\":21,\"pageCount\":3,\"items\":[" + sourceBody + "]}", false);
        assertEquals(3, adapter.listPaths(2, 10).pageCount());
        assertEquals("/v3/paths/list?page=2&itemsPerPage=10", calls.getLast().uri());
        respond = call -> new Reply(200, "{\"itemCount\":21,\"pageCount\":3,\"items\":[" + config(false).body() + "]}", false);
        assertEquals(21, adapter.listConfigurations(1, 10).itemCount());
        assertEquals("/v3/config/paths/list?page=1&itemsPerPage=10", calls.getLast().uri());
        assertThrows(IllegalArgumentException.class, () -> adapter.listPaths(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> adapter.listConfigurations(0, 101));
    }

    @Test
    void missingConfigurationAndSourceAreDifferentFromWrongEndpoint() {
        respond = call -> error(404, "path configuration not found");
        assertTrue(adapter.getPath(session).isEmpty());
        respond = call -> error(404, "path not found");
        assertTrue(adapter.getSource(session).isEmpty());
        adapter.deletePath(session);
        respond = call -> error(404, "unknown endpoint");
        unavailable(() -> adapter.getPath(session));
        unavailable(() -> adapter.deletePath(session));
        respond = call -> new Reply(200, "{\"name\":\"live/" + session + "\",\"online\":false,\"source\":null}", false);
        assertFalse(adapter.getSource(session).orElseThrow().online());
    }

    @Test
    void kicksTheActualConnectionIdUsingItsProtocol() {
        UUID connection = UUID.randomUUID();
        respond = call -> new Reply(200, "{\"status\":\"ok\"}", false);
        adapter.kick(MediaSourceType.RTMP_CONN, connection);
        adapter.kick(MediaSourceType.RTMPS_CONN, connection);
        assertEquals("/v3/rtmp/conns/kick/" + connection, calls.getFirst().uri());
        assertEquals("/v3/rtmps/conns/kick/" + connection, calls.getLast().uri());
        respond = call -> error(404, "connection not found");
        adapter.kick(MediaSourceType.RTMP_CONN, connection);
    }

    @Test
    void authServerMalformedAndRedirectResponsesFailWithoutRetryOrLeakingSecrets() {
        for (int status : List.of(401, 403, 500, 302)) {
            calls.clear();
            respond = call -> error(status, "sensitive-upstream-details");
            var exception = unavailable(() -> adapter.getPath(session));
            assertFalse(exception.toString().contains("sensitive-upstream-details"));
            assertNull(exception.getCause());
            assertEquals(1, calls.size());
        }
        for (String body : List.of("not JSON", "{}", "{\"name\":\"other\",\"source\":\"publisher\",\"overridePublisher\":false}")) {
            respond = call -> new Reply(200, body, false);
            unavailable(() -> adapter.getPath(session));
        }
    }

    @Test
    void requestTimeoutIsBoundedAndDoesNotRetry() {
        respond = call -> new Reply(200, config(false).body(), true);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> unavailable(() -> adapter.getPath(session)));
        assertEquals(1, calls.size());
    }

    @Test
    void rejectsControlUrlsWithCredentialsQueryOrPathAndNonpositiveTimeouts() {
        var configuration = new WebClientConfig();
        for (String url : List.of("ftp://host", "http://user:secret@host", "http://host?key=secret", "http://host/admin", "http://host#fragment")) {
            var properties = new MediaControlProperties();
            properties.setBaseUrl(url);
            assertThrows(IllegalStateException.class, () -> configuration.mediaControlWebClient(properties));
        }
        var properties = new MediaControlProperties();
        properties.setRequestTimeout(Duration.ZERO);
        assertThrows(IllegalStateException.class, () -> configuration.mediaControlWebClient(properties));
    }

    private Reply config(boolean override) {
        return new Reply(200, "{\"name\":\"live/" + session + "\",\"source\":\"publisher\",\"overridePublisher\":" + override + "}", false);
    }

    private Reply error(int status, String error) {
        return new Reply(status, "{\"status\":\"error\",\"error\":\"" + error + "\"}", false);
    }

    private AppException unavailable(Executable action) {
        var exception = assertThrows(AppException.class, action);
        assertEquals(ErrorCode.MEDIA_SERVER_UNAVAILABLE, exception.getErrorCode());
        return exception;
    }

    private record Call(
            String method,
            String uri,
            String authorization,
            String body
    ) {}

    private record Reply(
            int status,
            String body,
            boolean delay
    ) {}
}
