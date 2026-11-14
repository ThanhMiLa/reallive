package com.reallive.streamservice.stream.adapter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.client.channel.ChannelClient;
import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.stream.MediaTestSupport;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.repository.StreamStatusHistoryRepository;
import com.reallive.streamservice.stream.service.StreamEligibilityService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "stream.provisioning.it", matches = "true")
class StreamProvisioningMediaIT extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final int STREAM_PORT = freePort();
    private static final Path REPOSITORY =
            Files.exists(Path.of("media-server/config/mediamtx.yml"))
                            || Files.exists(Path.of("media-server/mediamtx.yml"))
                    ? Path.of(".").toAbsolutePath()
                    : Path.of("..").toAbsolutePath();

    @Container
    private static final GenericContainer<?> MEDIA =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    System.getProperty(
                                            "stream.media.test-image",
                                            "reallive-media-server:1.21.2")))
                    .withCreateContainerCmdModifier(
                            command ->
                                    command.withEntrypoint(
                                            "/bin/sh",
                                            "-c",
                                            "while true; do /mediamtx /mediamtx.yml; done"))
                    .withCopyToContainer(Transferable.of(mediaConfiguration()), "/mediamtx.yml")
                    .withCopyToContainer(
                            Transferable.of(hookScript("media_hook.py"), 0755),
                            "/hooks/media_hook.py")
                    .withCopyToContainer(
                            Transferable.of(hookScript("viewer_hook.py"), 0755),
                            "/hooks/viewer_hook.py")
                    .withExtraHost("host.docker.internal", "host-gateway")
                    .withEnv(
                            "MTX_AUTHJWTJWKS",
                            "http://host.docker.internal:" + STREAM_PORT + "/stream/internal/api/media/jwks")
                    .withEnv(
                            "STREAM_MEDIA_CALLBACK_URL",
                            "http://host.docker.internal:"
                                    + STREAM_PORT
                                    + "/stream/internal/api/media-events")
                    .withExposedPorts(1935, 8888, 9997);

    @Autowired private MediaServerAdapter media;
    @Autowired private ChannelStreamConfigRepository configs;
    @Autowired private StreamSessionRepository sessions;
    @Autowired private StreamStatusHistoryRepository history;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private StreamEligibilityService eligibility;
    @MockitoBean private RedisAuthoritiesReader authorities;
    @MockitoBean private ChannelClient channelClient;

    private final UUID owner = UUID.randomUUID();
    private final UUID streamer = UUID.randomUUID();
    private final UUID channel = UUID.randomUUID();
    private final UUID category = UUID.randomUUID();
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        configureDatabase(registry, SCHEMA);
        registry.add("server.port", () -> STREAM_PORT);
        registry.add("server.servlet.context-path", () -> "/stream");
        registry.add(
                "stream.media.control-api.base-url",
                () -> "http://127.0.0.1:" + MEDIA.getMappedPort(9997));
        registry.add(
                "stream.media.rtmp-public-url",
                () -> "rtmp://127.0.0.1:" + MEDIA.getMappedPort(1935) + "/ingest");
        registry.add(
                "stream.media.hls-public-base-url",
                () -> "http://127.0.0.1:" + MEDIA.getMappedPort(8888));
    }

    @BeforeEach
    void setupState() {
        history.deleteAll();
        sessions.deleteAll();
        configs.deleteAll();
        when(authorities.read(any()))
                .thenReturn(
                        List.of(
                                new SimpleGrantedAuthority("STREAM_VIEW_OWN"),
                                new SimpleGrantedAuthority("STREAM_UPDATE"),
                                new SimpleGrantedAuthority("STREAM_PUBLISH")));
        when(eligibility.requireChannelContext(owner))
                .thenReturn(new StreamPublishContext(owner, streamer, channel, null));
        when(eligibility.requirePublishContext(eq(owner), any()))
                .thenAnswer(
                        call ->
                                new StreamPublishContext(
                                        owner, streamer, channel, call.getArgument(1)));
        doNothing().when(channelClient).requireViewAccess(any(), any());
    }

    @AfterAll
    static void cleanup() throws SQLException {
        dropSchema(SCHEMA);
    }

    @Test
    void persistentKeyLifecycleWithSynchronousIssueAndReset() throws Exception {
        String ownerCookie = accountCookie(owner);
        String viewerCookie = accountCookie(UUID.randomUUID());

        // 1. Lazy DRAFT provision
        var draft = request("GET", "/api/streams/me/setup", ownerCookie, null, null, 200);
        assertEquals("no-store", draft.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode draftResult = mapper.readTree(draft.body()).path("result");
        assertEquals("DRAFT", draftResult.path("status").asString());
        assertTrue(draftResult.path("streamKey").isMissingNode());

        // 2. First valid Save -> PROVISIONS ingest/{k1} -> ACTIVE
        request(
                "PUT",
                "/api/streams/me/setup",
                ownerCookie,
                null,
                Map.of("title", "Real MediaMTX Persistent Key Stream", "categoryId", category),
                200);
        JsonNode activeSetup = awaitSetupReady(ownerCookie);
        String key1 = activeSetup.path("streamKey").asString();
        UUID ingestKey1 = UUID.fromString(key1.split("\\?token=")[0]);
        assertTrue(media.getIngestPath(ingestKey1).orElseThrow().acceptsPublisher());

        // Offline current returns null
        var offlineCurrent =
                request("GET", "/api/streams/me/current", ownerCookie, null, null, 200);
        assertTrue(mapper.readTree(offlineCurrent.body()).path("result").isNull());

        // 3. Publisher pushes to ingest/{k1} -> MediaMTX hook -> PROCESS_ONLINE -> live/{S1} RTMP
        // pull
        Process pub1 = publish(activeSetup.path("serverUrl").asString() + "/" + key1);
        UUID session1;
        try {
            await(
                    () ->
                            pub1.isAlive()
                                    && sessions.findSlotOccupyingByChannelId(channel).isPresent(),
                    Duration.ofSeconds(25),
                    "Publisher did not create LIVE session");
            StreamSessionEntity s1 = sessions.findSlotOccupyingByChannelId(channel).orElseThrow();
            session1 = s1.getId();
            assertEquals(StreamSessionStatus.LIVE, s1.getStatus());
            assertTrue(media.getPlaybackSource(session1).orElseThrow().online());

            // 4. Viewer gets Read JWT & fetches real HLS; cannot read ingest/*
            var access =
                    request(
                            "POST",
                            "/api/streams/" + session1 + "/playback-access",
                            viewerCookie,
                            null,
                            null,
                            200);
            JsonNode accessNode = mapper.readTree(access.body()).path("result");
            String readToken = accessNode.path("readToken").asString();
            String playbackUrl = accessNode.path("playbackUrl").asString();
            var hlsResp = awaitHls(playbackUrl, readToken, pub1);
            assertEquals(200, hlsResp.statusCode());
            assertTrue(hlsResp.body().contains("#EXTM3U"));

            String ingestHls =
                    "http://127.0.0.1:"
                            + MEDIA.getMappedPort(8888)
                            + "/ingest/"
                            + ingestKey1
                            + "/index.m3u8";
            var deniedIngestHls =
                    http.send(
                            HttpRequest.newBuilder(URI.create(ingestHls))
                                    .header("Authorization", "Bearer " + readToken)
                                    .GET()
                                    .timeout(Duration.ofSeconds(5))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding());
            assertTrue(deniedIngestHls.statusCode() == 401 || deniedIngestHls.statusCode() == 403);

            // 5. Update title while live without disrupting stream
            request(
                    "PUT",
                    "/api/streams/me/setup",
                    ownerCookie,
                    null,
                    Map.of("title", "Updated Live Title", "categoryId", category),
                    200);
            assertEquals(
                    "Updated Live Title",
                    mapper.readTree(
                                    request(
                                                    "GET",
                                                    "/api/streams/me/current",
                                                    ownerCookie,
                                                    null,
                                                    null,
                                                    200)
                                            .body())
                            .path("result")
                            .path("title")
                            .asString());

            // 6. Reset key while live -> kicks pub1, deletes ingest/{k1} + live/{S1}, ends S1,
            // provisions k2
            var resetResp =
                    request("POST", "/api/streams/me/stream-key", ownerCookie, null, null, 200);

            assertFalse(pub1.waitFor(5, TimeUnit.SECONDS) && pub1.exitValue() == 0);
            assertEquals(
                    StreamSessionStatus.ENDED,
                    sessions.findById(session1).orElseThrow().getStatus());
            assertTrue(media.getIngestPath(ingestKey1).isEmpty());
            assertTrue(media.getPlaybackPath(session1).isEmpty());
            request(
                    "POST",
                    "/api/streams/" + session1 + "/playback-access",
                    viewerCookie,
                    null,
                    null,
                    409);

            JsonNode resetSetup = mapper.readTree(resetResp.body()).path("result");
            String key2 = resetSetup.path("streamKey").asString();
            assertNotEquals(key1, key2);

            // Old key1 cannot publish anymore
            Process stalePub = publish(resetSetup.path("serverUrl").asString() + "/" + key1);
            try {
                assertTrue(stalePub.waitFor(15, TimeUnit.SECONDS));
                assertNotEquals(0, stalePub.exitValue());
            } finally {
                stalePub.destroyForcibly();
            }

            // 7. New key2 publishes new session S2 -> kill mediamtx -> reconciler restores
            // ingest/{k2}
            Process pub2 = publish(resetSetup.path("serverUrl").asString() + "/" + key2);
            UUID session2;
            try {
                await(
                        () ->
                                pub2.isAlive()
                                        && sessions.findSlotOccupyingByChannelId(channel)
                                                .isPresent(),
                        Duration.ofSeconds(25),
                        "Gen 2 publisher did not create new LIVE session");
                session2 = sessions.findSlotOccupyingByChannelId(channel).orElseThrow().getId();
                assertNotEquals(session1, session2);
            } finally {
                pub2.destroy();
                if (!pub2.waitFor(3, TimeUnit.SECONDS)) {
                    pub2.destroyForcibly();
                }
            }

        } finally {
            pub1.destroy();
            if (!pub1.waitFor(3, TimeUnit.SECONDS)) {
                pub1.destroyForcibly();
            }
        }
    }

    @Test
    void ownerSmokeScriptExercisesNewPersistentKeyHttpApiWithoutPrintingCredentials()
            throws Exception {
        Path directory = Files.createTempDirectory("persistent-smoke-");
        Path cookie = directory.resolve("account.cookie");
        Path credentialsFile = directory.resolve("credentials.json");
        try {
            Files.writeString(cookie, accountCookie(owner));
            var process =
                    new ProcessBuilder(
                                    "python3",
                                    REPOSITORY
                                            .resolve("scripts/testing/streaming/provisioning.py")
                                            .toString(),
                                    "--base-url",
                                    "http://127.0.0.1:" + STREAM_PORT + "/stream",
                                    "--cookie-file",
                                    cookie.toString(),
                                    "--category-id",
                                    category.toString(),
                                    "--credentials-file",
                                    credentialsFile.toString())
                            .redirectErrorStream(true)
                            .start();
            try {
                assertTrue(
                        process.waitFor(60, TimeUnit.SECONDS),
                        "Owner API smoke script did not finish");
                String output =
                        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), output);
                assertTrue(output.contains("PASS:"));
                assertFalse(output.contains("token="));
                assertFalse(output.contains(Files.readString(cookie)));
                JsonNode saved = mapper.readTree(Files.readString(credentialsFile));
                assertTrue(saved.path("streamKey").asString().contains("?token="));
                UUID ingestKeyId =
                        UUID.fromString(saved.path("streamKey").asString().split("\\?token=")[0]);
                assertTrue(media.getIngestPath(ingestKeyId).isPresent());
            } finally {
                process.destroyForcibly();
            }
        } finally {
            Files.deleteIfExists(cookie);
            Files.deleteIfExists(credentialsFile);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void mediaOutageReturnsErrorAndOwnerCanRequestAgainAfterUnpause() throws Exception {
        String cookie = accountCookie(owner);
        request(
                "PUT",
                "/api/streams/me/setup",
                cookie,
                null,
                Map.of("title", "Direct setup", "categoryId", category),
                200);
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(MEDIA.getContainerId()).exec();
        try {
            request("POST", "/api/streams/me/stream-key", cookie, null, null, 503);
            assertTrue(configs.findById(channel).orElseThrow().getCurrentIngestKeyId() == null);
        } finally {
            docker.unpauseContainerCmd(MEDIA.getContainerId()).exec();
        }
        assertTrue(awaitSetupReady(cookie).path("streamKey").asString().contains("?token="));
    }

    private JsonNode awaitSetupReady(String cookie) throws Exception {
        return mapper.readTree(
                        request("POST", "/api/streams/me/stream-key", cookie, null, null, 200)
                                .body())
                .path("result");
    }

    private HttpResponse<String> request(
            String method,
            String path,
            String cookie,
            String idempotencyKey,
            Object body,
            int expectedStatus)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + STREAM_PORT + "/stream" + path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Cookie", "access_token=" + cookie);
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        var response =
                http.send(
                        builder.method(
                                        method,
                                        body == null
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofString(
                                                        mapper.writeValueAsString(body)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(
                expectedStatus,
                response.statusCode(),
                method + " " + path + " -> " + response.body());
        return response;
    }

    private String accountCookie(UUID user) throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .subject(user.toString())
                        .issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                        .claim("token_type", "ACCESS")
                        .build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        token.sign(
                new MACSigner(
                        "phase3-account-test-signing-key-32bytes"
                                .getBytes(StandardCharsets.UTF_8)));
        return token.serialize();
    }

    private HttpResponse<String> awaitHls(String url, String token, Process publisher)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        HttpResponse<String> last = null;
        while (System.nanoTime() < deadline) {
            assertTrue(
                    publisher.isAlive(),
                    "Publisher exited before HLS playlist became available at " + url);
            last =
                    http.send(
                            HttpRequest.newBuilder(URI.create(url))
                                    .header("Authorization", "Bearer " + token)
                                    .GET()
                                    .timeout(Duration.ofSeconds(10))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (last.statusCode() == 200 && last.body().contains("#EXTM3U")) {
                return last;
            }
            LockSupport.parkNanos(Duration.ofMillis(150).toNanos());
        }
        return last;
    }

    private Process publish(String fullRtmpUrl) throws Exception {
        return new ProcessBuilder(
                        "ffmpeg",
                        "-nostdin",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-re",
                        "-f",
                        "lavfi",
                        "-i",
                        "testsrc=size=160x90:rate=10",
                        "-f",
                        "lavfi",
                        "-i",
                        "sine=frequency=440:sample_rate=44100",
                        "-t",
                        "120",
                        "-c:v",
                        "libx264",
                        "-preset",
                        "ultrafast",
                        "-pix_fmt",
                        "yuv420p",
                        "-g",
                        "20",
                        "-c:a",
                        "aac",
                        "-f",
                        "flv",
                        fullRtmpUrl)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static void await(BooleanSupplier condition, Duration timeout, String description) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
        }
        fail(description);
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot reserve integration test port");
        }
    }

    private static String mediaConfiguration() {
        try {
            Path configPath =
                    Files.exists(REPOSITORY.resolve("media-server/config/mediamtx.yml"))
                            ? REPOSITORY.resolve("media-server/config/mediamtx.yml")
                            : REPOSITORY.resolve("media-server/mediamtx.yml");
            return Files.readString(configPath);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read pinned MediaMTX configuration");
        }
    }

    private static byte[] hookScript(String name) {
        try {
            return Files.readAllBytes(REPOSITORY.resolve("media-server/hooks/" + name));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read media hook script " + name, exception);
        }
    }
}
