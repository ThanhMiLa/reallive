package com.reallive.streamservice.stream.adapter.media.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.reallive.streamservice.common.configuration.MediaControlProperties;
import com.reallive.streamservice.common.configuration.WebClientConfig;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

class MediaMtxAdapterTest {

    private final UUID ingestKeyId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService executor;
    private MediaControlProperties properties;
    private MediaMtxAdapter adapter;
    private Function<Call, Reply> respond;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext(
                "/",
                exchange -> {
                    var call =
                            new Call(
                                    exchange.getRequestMethod(),
                                    exchange.getRequestURI().toString(),
                                    exchange.getRequestHeaders().getFirst("Authorization"),
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
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
        properties = new MediaControlProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setRequestTimeout(Duration.ofSeconds(2));
        adapter =
                new MediaMtxAdapter(
                        new WebClientConfig().mediaControlWebClient(properties),
                        properties,
                        mapper);
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void ensureIngestPathAddsPublisherWithoutControlApiCredentials() throws Exception {
        respond =
                call -> {
                    if (calls.size() == 1) {
                        return error(404, "path configuration not found");
                    }
                    return call.method().equals("POST")
                            ? new Reply(200, "{\"status\":\"ok\"}", false)
                            : ingestConfig(false);
                };
        adapter.ensureIngestPath(ingestKeyId);
        assertEquals(List.of("GET", "POST", "GET"), calls.stream().map(Call::method).toList());
        assertEquals("/v3/config/paths/add/ingest/" + ingestKeyId, calls.get(1).uri());
        var payload = mapper.readTree(calls.get(1).body());
        assertEquals("publisher", payload.path("source").asString());
        assertFalse(payload.path("overridePublisher").asBoolean());
        assertEquals("", payload.path("runOnRead").asString());
        assertEquals("", payload.path("runOnUnread").asString());
        for (Call call : calls) {
            assertNull(call.authorization());
        }
    }

    @Test
    void ensurePlaybackPathConfiguresInternalRtmpRelayAndDisablesLifecycleHooks() throws Exception {
        String relayJwt = "relay-read-jwt-token";
        respond =
                call -> {
                    if (calls.size() == 1) {
                        return error(404, "path configuration not found");
                    }
                    return call.method().equals("POST")
                            ? new Reply(200, "{\"status\":\"ok\"}", false)
                            : playbackConfig(
                                    "rtmp://127.0.0.1:1935/ingest/"
                                            + ingestKeyId
                                            + "?token="
                                            + relayJwt,
                                    "",
                                    "");
                };
        adapter.ensurePlaybackPath(sessionId, ingestKeyId, relayJwt);
        assertEquals("/v3/config/paths/add/live/" + sessionId, calls.get(1).uri());
        var payload = mapper.readTree(calls.get(1).body());
        assertEquals(
                "rtmp://127.0.0.1:1935/ingest/" + ingestKeyId + "?token=" + relayJwt,
                payload.path("source").asString());
        assertFalse(payload.path("sourceOnDemand").asBoolean());
        assertEquals("", payload.path("runOnOnline").asString());
        assertEquals("", payload.path("runOnOffline").asString());
    }

    @Test
    void ensurePlaybackPathRejectsMismatchedSourceOrNonEmptyLifecycleHooks() {
        String relayJwt = "relay-read-jwt-token";
        respond =
                call ->
                        playbackConfig(
                                "rtmp://127.0.0.1:1935/ingest/" + ingestKeyId + "?token=other",
                                "",
                                "");
        unavailable(() -> adapter.ensurePlaybackPath(sessionId, ingestKeyId, relayJwt));

        respond =
                call ->
                        playbackConfig(
                                "rtmp://127.0.0.1:1935/ingest/"
                                        + ingestKeyId
                                        + "?token="
                                        + relayJwt,
                                "python3 /hooks/media_hook.py online",
                                "");
        unavailable(() -> adapter.ensurePlaybackPath(sessionId, ingestKeyId, relayJwt));
    }

    @Test
    void readsPublisherAndRtmpRelaySourcesAndServerInfoSafely() {
        UUID source = UUID.randomUUID();
        Instant onlineTime = Instant.parse("2026-11-13T10:15:30Z");
        String ingestBody =
                "{\"name\":\"ingest/"
                        + ingestKeyId
                        + "\",\"online\":true,\"onlineTime\":\""
                        + onlineTime
                        + "\",\"source\":{\"id\":\""
                        + source
                        + "\",\"type\":\"rtmpConn\"}}";
        respond = call -> new Reply(200, ingestBody, false);
        var observed = adapter.getIngestSource(ingestKeyId).orElseThrow();
        assertTrue(observed.online());
        assertEquals(source, observed.sourceId());
        assertEquals(MediaSourceType.RTMP_CONN, observed.sourceType());
        assertEquals(onlineTime, observed.onlineTime());

        String playbackBody =
                "{\"name\":\"live/"
                        + sessionId
                        + "\",\"online\":true,\"onlineTime\":\"2026-11-13T10:15:31Z\",\"source\":{\"id\":\"\",\"type\":\"rtmpSource\"}}";
        respond = call -> new Reply(200, playbackBody, false);
        var playbackObserved = adapter.getPlaybackSource(sessionId).orElseThrow();
        assertTrue(playbackObserved.online());
        assertEquals(MediaSourceType.RTMP_SOURCE, playbackObserved.sourceType());
        assertNull(playbackObserved.sourceId());

        respond =
                call ->
                        new Reply(
                                200,
                                "{\"version\":\"v1.21.2\",\"started\":\"2026-11-13T09:00:00Z\"}",
                                false);
        var info = adapter.getServerInfo();
        assertEquals("v1.21.2", info.version());
        assertEquals("2026-11-13T09:00:00Z", info.started());
    }

    @Test
    void missingConfigurationAndSourceAreDifferentFromWrongEndpoint() {
        respond = call -> error(404, "path configuration not found");
        assertTrue(adapter.getIngestPath(ingestKeyId).isEmpty());
        respond = call -> error(404, "path not found");
        assertTrue(adapter.getPlaybackSource(sessionId).isEmpty());
        adapter.deletePlaybackPath(sessionId);
        respond = call -> error(404, "unknown endpoint");
        unavailable(() -> adapter.getIngestPath(ingestKeyId));
        unavailable(() -> adapter.deleteIngestPath(ingestKeyId));
        respond =
                call ->
                        new Reply(
                                200,
                                "{\"name\":\"ingest/"
                                        + ingestKeyId
                                        + "\",\"online\":false,\"source\":null}",
                                false);
        assertFalse(adapter.getIngestSource(ingestKeyId).orElseThrow().online());
    }

    @Test
    void kicksPublisherConnectionsOnlyAndNeverKicksStaticRelaySource() {
        UUID connection = UUID.randomUUID();
        respond = call -> new Reply(200, "{\"status\":\"ok\"}", false);
        adapter.kick(MediaSourceType.RTMP_CONN, connection);
        adapter.kick(MediaSourceType.RTMPS_CONN, connection);
        assertEquals("/v3/rtmp/conns/kick/" + connection, calls.getFirst().uri());
        assertEquals("/v3/rtmps/conns/kick/" + connection, calls.getLast().uri());
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.kick(MediaSourceType.RTMP_SOURCE, connection));
    }

    @Test
    void authServerMalformedAndRedirectResponsesNeverLeakRelayTokens() {
        String secretToken = "secret-relay-token-should-not-leak";
        for (int status : List.of(401, 403, 500, 302)) {
            calls.clear();
            respond = call -> error(status, "failed on token=" + secretToken);
            var exception =
                    unavailable(
                            () -> adapter.ensurePlaybackPath(sessionId, ingestKeyId, secretToken));
            assertFalse(exception.toString().contains(secretToken));
            assertNull(exception.getCause());
        }
        respond = call -> new Reply(200, "not JSON", false);
        unavailable(() -> adapter.getIngestPath(ingestKeyId));
    }

    @Test
    void requestTimeoutIsBoundedAndDoesNotRetry() {
        respond = call -> new Reply(200, ingestConfig(false).body(), true);
        assertTimeoutPreemptively(
                Duration.ofSeconds(3), () -> unavailable(() -> adapter.getIngestPath(ingestKeyId)));
        assertEquals(1, calls.size());
    }

    private Reply ingestConfig(boolean override) {
        return new Reply(
                200,
                "{\"name\":\"ingest/"
                        + ingestKeyId
                        + "\",\"source\":\"publisher\",\"overridePublisher\":"
                        + override
                        + "}",
                false);
    }

    private Reply playbackConfig(String source, String runOnOnline, String runOnOffline) {
        return new Reply(
                200,
                "{\"name\":\"live/"
                        + sessionId
                        + "\",\"source\":\""
                        + source
                        + "\",\"overridePublisher\":true,\"sourceOnDemand\":false,\"runOnOnline\":\""
                        + runOnOnline
                        + "\",\"runOnOffline\":\""
                        + runOnOffline
                        + "\"}",
                false);
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
