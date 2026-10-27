package com.reallive.streamservice.stream.adapter.media;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.client.channel.ChannelClient;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.repository.StreamStatusHistoryRepository;
import com.reallive.streamservice.stream.service.StreamEligibilityService;
import com.reallive.streamservice.stream.service.StreamSessionClosureService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Testcontainers
@EnabledIfSystemProperty(named = "stream.provisioning.it", matches = "true")
class StreamProvisioningMediaIT extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final int STREAM_PORT = freePort();
    private static final Path REPOSITORY = Files.exists(Path.of("mediamtx/mediamtx.yml"))
            ? Path.of(".").toAbsolutePath() : Path.of("..").toAbsolutePath();
    @Container
    private static final GenericContainer<?> MEDIA = new GenericContainer<>(DockerImageName.parse("reallive-mediamtx:1.21.2"))
            .withCopyToContainer(Transferable.of(mediaConfiguration()), "/mediamtx.yml")
            .withExtraHost("host.docker.internal", "host-gateway")
            .withEnv("MTX_AUTHJWTJWKS", "http://host.docker.internal:" + STREAM_PORT + "/stream/api/media/jwks")
            .withEnv("STREAM_MEDIA_CALLBACK_URL", "http://host.docker.internal:" + STREAM_PORT + "/stream/api/media/events")
            .withExposedPorts(1935, 8888, 9997);

    @Autowired private MediaServerAdapter media;
    @Autowired private StreamSessionRepository sessions;
    @Autowired private StreamStatusHistoryRepository history;
    @Autowired private StreamSessionClosureService closure;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private StreamEligibilityService eligibility;
    @MockitoBean private ChannelClient channels;
    @MockitoBean private RedisAuthoritiesReader authorities;
    private final UUID owner = UUID.randomUUID();
    private final UUID streamer = UUID.randomUUID();
    private final UUID channel = UUID.randomUUID();
    private final UUID category = UUID.randomUUID();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        configureDatabase(registry, SCHEMA);
        registry.add("server.port", () -> STREAM_PORT);
        registry.add("server.servlet.context-path", () -> "/stream");
        registry.add("stream.media.control-api.base-url", () -> "http://127.0.0.1:" + MEDIA.getMappedPort(9997));
        registry.add("stream.media.rtmp-public-url", () -> "rtmp://127.0.0.1:" + MEDIA.getMappedPort(1935) + "/live");
    }

    @BeforeEach
    void eligibility() {
        when(authorities.read(any())).thenReturn(List.of());
        when(eligibility.requirePublishContext(eq(owner), any())).thenAnswer(call ->
                new StreamPublishContext(owner, streamer, channel, call.getArgument(1)));
    }

    @AfterAll
    static void cleanup() throws SQLException {
        dropSchema(SCHEMA);
    }

    @Test
    void realHttpCreateUpdateReplayCredentialsAndActualPublisherUseTheProvisionedPath() throws Exception {
        String cookie = accountCookie(owner);
        UUID requestId = UUID.randomUUID();
        var created = request("POST", "/api/streams", cookie, Map.of("requestId", requestId, "title", "API stream", "categoryId", category), 201);
        assertEquals("no-store", created.headers().firstValue("Cache-Control").orElseThrow());
        var result = mapper.readTree(created.body()).path("result");
        UUID id = UUID.fromString(result.path("session").path("id").asString());
        Process publisher = null;
        try {
            assertEquals("READY", result.path("session").path("status").asString());
            assertTrue(result.path("session").path("publishReady").asBoolean());
            assertTrue(media.getPath(id).orElseThrow().acceptsPublisher());
            assertTrue(media.getSource(id).isEmpty() || !media.getSource(id).orElseThrow().online());
            assertTrue(media.listConfigurations(0, 1).itemCount() >= 1);
            var retry = request("POST", "/api/streams", cookie, Map.of("requestId", requestId, "title", "Ignored retry", "categoryId", category), 200);
            assertEquals(id.toString(), mapper.readTree(retry.body()).path("result").path("session").path("id").asString());
            assertEquals("API stream", mapper.readTree(retry.body()).path("result").path("session").path("title").asString());
            request("POST", "/api/streams", cookie, Map.of("requestId", UUID.randomUUID(), "title", "Double create", "categoryId", category), 409);
            request("POST", "/api/streams/" + id + "/publish-credentials", accountCookie(UUID.randomUUID()), null, 403);
            var current = request("GET", "/api/streams/me/current", cookie, null, 200);
            assertFalse(current.body().contains("token="));
            assertFalse(current.body().contains("streamKey"));
            var credentials = result.path("publishCredentials");
            publisher = publish(credentials);
            var source = awaitSource(id, publisher, Duration.ofSeconds(20));
            assertTrue(media.listPaths(0, 1).items().stream().anyMatch(observed -> observed.sourceId().equals(source.sourceId())));
            media.kick(MediaSourceType.RTMP_CONN, UUID.randomUUID());
            media.ensurePath(id);
            assertEquals(source.sourceId(), media.getSource(id).orElseThrow().sourceId(), "Ensure must preserve the live source");
            request("PATCH", "/api/streams/" + id, cookie, Map.of("title", "Updated while publishing"), 200);
            assertEquals(source.sourceId(), media.getSource(id).orElseThrow().sourceId());
            var fresh = request("POST", "/api/streams/" + id + "/publish-credentials", cookie, null, 200);
            assertEquals("no-store", fresh.headers().firstValue("Cache-Control").orElseThrow());
            assertNotEquals(credentials.path("streamKey").asString(), mapper.readTree(fresh.body()).path("result").path("streamKey").asString());
            assertEquals(StreamSessionStatus.READY, sessions.findById(id).orElseThrow().getStatus(), "Phase 3 does not fabricate LIVE without a lifecycle handler");
            assertEquals(StreamSessionStatus.ENDED, closure.end(id, owner, "Phase 3 test cleanup after real closure").status());
            assertTrue(media.getPath(id).isEmpty());
            assertTrue(media.getSource(id).isEmpty() || !media.getSource(id).orElseThrow().online());
            request("POST", "/api/streams/" + id + "/publish-credentials", cookie, null, 409);
            request("POST", "/api/streams", cookie, Map.of("requestId", requestId, "title", "Old session", "categoryId", category), 409);
            var next = request("POST", "/api/streams", cookie, Map.of("requestId", UUID.randomUUID(), "title", "Missing path", "categoryId", category), 201);
            UUID nextId = UUID.fromString(mapper.readTree(next.body()).path("result").path("session").path("id").asString());
            try {
                media.deletePath(nextId);
                request("POST", "/api/streams/" + nextId + "/publish-credentials", cookie, null, 503);
                assertFalse(mapper.readTree(request("GET", "/api/streams/me/current", cookie, null, 200).body()).path("result").path("publishReady").asBoolean());
                assertTrue(media.getPath(nextId).isEmpty(), "Credential issuance must not restore missing paths");
            } finally {
                closure.end(nextId, owner, "Missing path test cleanup");
            }
        } finally {
            if (publisher != null) {
                publisher.destroy();
                if (!publisher.waitFor(3, TimeUnit.SECONDS)) { publisher.destroyForcibly(); }
            }
            media.deletePath(id);
        }
    }

    @Test
    void ownerSmokeScriptExercisesTheRealHttpApiWithoutPrintingCredentials() throws Exception {
        Path directory = Files.createTempDirectory("phase3-smoke-");
        Path cookie = directory.resolve("account.cookie");
        Path state = directory.resolve("state.json");
        Path credentials = directory.resolve("credentials.json");
        try {
            Files.writeString(cookie, accountCookie(owner));
            var process = new ProcessBuilder("python3", REPOSITORY.resolve("scripts/testing/streaming/provisioning.py").toString(),
                    "--base-url", "http://127.0.0.1:" + STREAM_PORT + "/stream", "--cookie-file", cookie.toString(),
                    "--category-id", category.toString(), "--state-file", state.toString(), "--credentials-file", credentials.toString())
                    .redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Owner API smoke script did not finish");
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), output);
                assertTrue(output.contains("PASS:"));
                assertFalse(output.contains("token="));
                assertFalse(output.contains(Files.readString(cookie)));
                UUID id = UUID.fromString(mapper.readTree(Files.readString(state)).path("sessionId").asString());
                assertTrue(media.getPath(id).isPresent());
                assertEquals(id.toString(), mapper.readTree(Files.readString(credentials)).path("sessionId").asString());
            } finally {
                process.destroyForcibly();
            }
        } finally {
            if (Files.exists(state) && mapper.readTree(Files.readString(state)).has("sessionId")) {
                closure.end(UUID.fromString(mapper.readTree(Files.readString(state)).path("sessionId").asString()), owner, "Smoke script cleanup");
            }
            Files.deleteIfExists(cookie);
            Files.deleteIfExists(state);
            Files.deleteIfExists(credentials);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void realMediaTimeoutReturns503AndRollsBackBeforeTheUserRetries() throws Exception {
        var docker = DockerClientFactory.instance().client();
        String containerId = MEDIA.getContainerId();
        String cookie = accountCookie(owner);
        var body = Map.of("requestId", UUID.randomUUID(), "title", "Media timeout", "categoryId", category);
        long sessionsBefore = sessions.count();
        long historyBefore = history.count();
        docker.pauseContainerCmd(containerId).exec();
        try {
            var response = request("POST", "/api/streams", cookie, body, 503);
            assertEquals(1014, mapper.readTree(response.body()).path("code").asInt());
            assertTrue(mapper.readTree(response.body()).path("result").isNull());
            assertFalse(response.body().contains("token="));
            assertEquals(sessionsBefore, sessions.count());
            assertEquals(historyBefore, history.count());
            assertTrue(sessions.findSlotOccupyingByChannelId(channel).isEmpty());
        } finally {
            docker.unpauseContainerCmd(containerId).exec();
        }
        var created = request("POST", "/api/streams", cookie, body, 201);
        UUID id = UUID.fromString(mapper.readTree(created.body()).path("result").path("session").path("id").asString());
        assertTrue(media.getPath(id).isPresent());
        closure.end(id, owner, "Timeout retry test cleanup");
    }

    @Test
    @EnabledIfSystemProperty(named = "stream.media.obs", matches = "true")
    void obsPublishesCredentialsObtainedFromTheRealCreateApi() throws Exception {
        var created = request("POST", "/api/streams", accountCookie(owner),
                Map.of("requestId", UUID.randomUUID(), "title", "OBS phase 3 acceptance", "categoryId", category), 201);
        JsonNode result = mapper.readTree(created.body()).path("result");
        UUID id = UUID.fromString(result.path("session").path("id").asString());
        var credentials = result.path("publishCredentials");
        HttpServer handoff = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var observed = new AtomicReference<MediaPathStatus>();
        var completed = new CountDownLatch(1);
        String html = """
                <!doctype html><html lang="en"><meta charset="utf-8"><title>Phase 3 OBS acceptance</title>
                <h1>OBS — isolated phase 3 test</h1><p>API session: %s</p>
                <p><label>Server <input id="server" value="%s" readonly></label><button onclick="copy('server')">Copy server</button></p>
                <p><label>Stream key <input id="key" type="password" value="%s" readonly></label><button onclick="copy('key')">Copy stream key</button></p>
                <p id="status">Waiting for OBS. Use a synthetic source; no screen or microphone capture is needed.</p>
                <button id="complete" disabled onclick="completeTest()">Confirm OBS is streaming and finish test</button>
                <script>
                async function copy(id){await navigator.clipboard.writeText(document.getElementById(id).value);}
                async function completeTest(){const r=await fetch('/complete',{method:'POST'});if(r.ok){document.getElementById('status').textContent='Confirmed; test will close the path and clean up.';clearInterval(poll);}}
                const poll=setInterval(async()=>{try{const r=await fetch('/status');const s=await r.json();if(s.online){document.getElementById('status').textContent='OBS source online; DB status READY as expected before phase 4. Source: '+s.sourceId;document.getElementById('complete').disabled=false;}}catch{}},1000);
                </script>
                </html>
                """.formatted(id, credentials.path("serverUrl").asString(), credentials.path("streamKey").asString());
        handoff.createContext("/", exchange -> {
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        handoff.createContext("/status", exchange -> {
            var source = observed.get();
            byte[] body = mapper.writeValueAsBytes(source == null ? Map.of("online", false)
                    : Map.of("online", true, "sourceId", source.sourceId()));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        handoff.createContext("/complete", exchange -> {
            boolean accepted = exchange.getRequestMethod().equals("POST") && observed.get() != null;
            exchange.sendResponseHeaders(accepted ? 200 : 409, -1);
            exchange.close();
            if (accepted) { completed.countDown(); }
        });
        Path handoffFile = REPOSITORY.resolve(".local/streaming/phase3-obs-handoff.json");
        Path resultFile = REPOSITORY.resolve(".local/streaming/phase3-obs-result.json");
        try {
            handoff.start();
            Files.createDirectories(handoffFile.getParent());
            Files.writeString(handoffFile, mapper.writeValueAsString(Map.of("url", "http://127.0.0.1:" + handoff.getAddress().getPort(), "sessionId", id)));
            Files.writeString(resultFile, mapper.writeValueAsString(Map.of("status", "RUNNING", "sessionId", id, "startedAt", Instant.now())));
            var source = awaitSource(id, null, Duration.ofMinutes(10));
            request("PATCH", "/api/streams/" + id, accountCookie(owner), Map.of("title", "OBS verified through phase 3 API"), 200);
            assertEquals(source.sourceId(), media.getSource(id).orElseThrow().sourceId());
            assertEquals(StreamSessionStatus.READY, sessions.findById(id).orElseThrow().getStatus());
            observed.set(source);
            assertTrue(completed.await(10, TimeUnit.MINUTES), "OBS operator did not confirm acceptance");
            assertEquals(source.sourceId(), media.getSource(id).orElseThrow().sourceId(), "Confirmed source must still be online");
            closure.end(id, owner, "OBS acceptance confirmed; media closure before report");
            Files.writeString(resultFile, mapper.writeValueAsString(
                    Map.of("status", "PASS", "sessionId", id, "sourceId", source.sourceId(), "sourceType", source.sourceType().getValue(),
                            "dbStatusBeforeCleanup", "READY", "metadataUpdatePreservedSource", true, "cleanup", "ENDED/CLOSED", "observedAt", Instant.now())));
        } catch (Exception | AssertionError exception) {
            Files.writeString(resultFile, mapper.writeValueAsString(Map.of("status", "FAIL", "sessionId", id, "observedAt", Instant.now())));
            throw exception;
        } finally {
            handoff.stop(0);
            Files.deleteIfExists(handoffFile);
            closure.end(id, owner, "OBS acceptance cleanup");
        }
    }

    private HttpResponse<String> request(String method, String path, String cookie, Object body, int expectedStatus) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + STREAM_PORT + "/stream" + path))
                .timeout(Duration.ofSeconds(20)).header("Cookie", "access_token=" + cookie);
        if (body != null) { builder.header("Content-Type", "application/json"); }
        var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), method + " " + path);
        return response;
    }

    private String accountCookie(UUID user) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(user.toString()).issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900))).claim("token_type", "ACCESS").build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        token.sign(new MACSigner("phase3-account-test-signing-key-32bytes".getBytes(StandardCharsets.UTF_8)));
        return token.serialize();
    }

    private Process publish(JsonNode credentials) throws Exception {
        return new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-re", "-f", "lavfi", "-i",
                "testsrc=size=160x90:rate=10", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "60", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-g", "20",
                "-c:a", "aac", "-f", "flv", credentials.path("serverUrl").asString() + "/" + credentials.path("streamKey").asString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private MediaPathStatus awaitSource(UUID id, Process publisher, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (publisher != null) { assertTrue(publisher.isAlive(), "Publisher exited before becoming online"); }
            var source = media.getSource(id);
            if (source.isPresent() && source.get().online()) { return source.get(); }
            LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
        }
        throw new AssertionError("No publisher became online before the acceptance deadline");
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (Exception exception) { throw new IllegalStateException("Cannot reserve integration test port"); }
    }

    private static String mediaConfiguration() {
        try { return Files.readString(REPOSITORY.resolve("mediamtx/mediamtx.yml")); }
        catch (Exception exception) { throw new IllegalStateException("Cannot read pinned MediaMTX configuration"); }
    }
}
