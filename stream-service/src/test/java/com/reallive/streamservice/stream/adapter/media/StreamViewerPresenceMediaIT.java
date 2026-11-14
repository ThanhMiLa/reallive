package com.reallive.streamservice.stream.adapter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.common.configuration.MediaKeyMaterial;
import com.reallive.streamservice.common.configuration.MediaProperties;
import com.reallive.streamservice.stream.MediaTestSupport;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.adapter.media.impl.MediaJwtIssuerImpl;
import com.reallive.streamservice.stream.adapter.redis.StreamViewerPresenceStore;
import com.reallive.streamservice.stream.dto.StreamPublishContext;
import com.reallive.streamservice.stream.dto.request.SaveStreamSetupRequest;
import com.reallive.streamservice.stream.repository.ChannelStreamConfigRepository;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.repository.StreamStatusHistoryRepository;
import com.reallive.streamservice.stream.scheduler.CcuReconciliationScheduler;
import com.reallive.streamservice.stream.service.StreamEligibilityService;
import com.reallive.streamservice.stream.service.StreamSetupService;

import org.junit.jupiter.api.AfterAll;
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
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "stream.ccu.it", matches = "true")
class StreamViewerPresenceMediaIT extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final int STREAM_PORT = freePort();

    private static final Path TOKEN_DIRECTORY = tokenDirectory();
    private static final Path REPOSITORY =
            Files.exists(Path.of("media-server/config/mediamtx.yml"))
                    ? Path.of(".").toAbsolutePath()
                    : Path.of("..").toAbsolutePath();

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withCreateContainerCmdModifier(
                            command ->
                                    command.withEntrypoint(
                                            "/bin/sh",
                                            "-c",
                                            "while true; do redis-server --save '' --appendonly no;"
                                                    + " done"))
                    .withExposedPorts(6379);

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
                    .withFileSystemBind(
                            TOKEN_DIRECTORY.toString(), "/run/media-hook", BindMode.READ_ONLY)
                    .withExtraHost("host.docker.internal", "host-gateway")
                    .withEnv(
                            "MTX_AUTHJWTJWKS",
                            "http://host.docker.internal:" + STREAM_PORT + "/stream/internal/api/media/jwks")
                    .withEnv(
                            "STREAM_MEDIA_CALLBACK_URL",
                            "http://host.docker.internal:"
                                    + STREAM_PORT
                                    + "/stream/internal/api/media-events")
                    .withEnv("STREAM_CCU_ENABLED", "true")
                    .withEnv(
                            "STREAM_VIEWER_CALLBACK_URL",
                            "http://host.docker.internal:"
                                    + STREAM_PORT
                                    + "/stream/internal/api/viewer-events")
                    .withEnv("MEDIA_CONTROL_TOKEN_FILE", "/run/media-hook/control.jwt")
                    .withExposedPorts(1935, 8888, 9997);

    @Autowired private StreamSetupService setupService;
    @Autowired private ChannelStreamConfigRepository configs;
    @Autowired private StreamSessionRepository sessions;
    @Autowired private StreamStatusHistoryRepository history;
    @Autowired private MediaJwtIssuer issuer;
    @Autowired private MediaKeyMaterial keys;
    @Autowired private MediaProperties properties;
    @Autowired private StreamViewerPresenceStore presence;
    @Autowired private CcuReconciliationScheduler reconciliation;
    @Autowired private CcuControlTokenPublisher tokenPublisher;
    @Autowired private ObjectMapper mapper;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redis;
    @MockitoBean private StreamEligibilityService eligibility;
    @MockitoBean private RedisAuthoritiesReader authorities;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        configureDatabase(registry, SCHEMA);
        registry.add("server.port", () -> STREAM_PORT);
        registry.add("server.servlet.context-path", () -> "/stream");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("stream.ccu.enabled", () -> "true");
        registry.add("stream.ccu.control-token-directory", TOKEN_DIRECTORY::toString);
        registry.add("stream.ccu.reconcile-delay", () -> "3600000");
        registry.add("stream.ccu.token-publish-delay", () -> "3600000");
        registry.add("stream.media.control-token-ttl", () -> "2h");
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

    @AfterAll
    static void cleanup() throws Exception {
        MEDIA.stop();
        REDIS.stop();
        dropSchema(SCHEMA);
        try (var paths = Files.walk(TOKEN_DIRECTORY)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void realHooksDeduplicateAccountsIgnoreRelayAndWorkerAndRecoverAfterRedisOrMediaRestart()
            throws Exception {
        when(authorities.read(any())).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
        UUID owner = UUID.randomUUID();
        UUID streamer = UUID.randomUUID();
        UUID channel = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        when(eligibility.requireChannelContext(owner))
                .thenReturn(new StreamPublishContext(owner, streamer, channel, null));
        when(eligibility.requirePublishContext(eq(owner), any()))
                .thenAnswer(
                        call ->
                                new StreamPublishContext(
                                        owner, streamer, channel, call.getArgument(1)));

        setupService.saveSetup(
                owner, new SaveStreamSetupRequest("Isolated CCU two-path stream", category));
        var setup = setupService.issueStreamKey(owner);

        UUID viewerA = UUID.randomUUID();
        UUID viewerB = UUID.randomUUID();

        Process publisher = publish(setup.serverUrl() + "/" + setup.streamKey());
        UUID session = null;
        try {
            await(
                    () ->
                            publisher.isAlive()
                                    && sessions.findSlotOccupyingByChannelId(channel).isPresent(),
                    Duration.ofSeconds(25),
                    "Real ONLINE hook did not transition two-path stream to LIVE");
            session = sessions.findSlotOccupyingByChannelId(channel).orElseThrow().getId();
            final UUID liveSession = session;

            // Technical RTMP relay reading ingest/{k} must not create any viewer presence
            await(
                    () -> {
                        reconciliation.reconcile();
                        return Objects.equals(0L, presence.count(liveSession));
                    },
                    Duration.ofSeconds(10),
                    "Empty viewer presence did not become ready");
            assertEquals(0L, metadataCount(liveSession, viewerA));
            assertTrue(
                    presence.readers(liveSession).isEmpty(),
                    "Technical relay reader must not enter viewer index");
            assertTrue(
                    Files.isRegularFile(TOKEN_DIRECTORY.resolve("control.jwt")),
                    "Hook credential must be published before viewing");

            try (var ownerPreview =
                            open(liveSession, issuer.issueRead(liveSession, owner).value());
                    var worker = open(liveSession, issuer.issueWorkerRead(liveSession).value());
                    var first = open(liveSession, issuer.issueRead(liveSession, viewerA).value())) {
                UUID firstReader = awaitReader(liveSession, viewerA, 1);
                awaitCount(liveSession, viewerA, 1);

                byte[] before = credentialDigest();
                tokenPublisher.publish();
                assertFalse(
                        Arrays.equals(before, credentialDigest()),
                        "Publishing must replace the hook credential");
                var shortToken = shortRead(liveSession, viewerA);
                try (var refreshed = open(liveSession, shortToken.value());
                        var secondUser =
                                open(liveSession, issuer.issueRead(liveSession, viewerB).value())) {
                    awaitReader(liveSession, viewerA, 2);
                    UUID refreshedReader =
                            presence.readers(liveSession).entrySet().stream()
                                    .filter(
                                            entry ->
                                                    entry.getValue().equals(viewerA)
                                                            && !entry.getKey().equals(firstReader))
                                    .map(Map.Entry::getKey)
                                    .findFirst()
                                    .orElseThrow();
                    UUID secondUserReader = awaitReader(liveSession, viewerB, 1);
                    awaitCount(liveSession, viewerA, 2);
                    assertFalse(
                            presence.readers(liveSession).containsValue(owner),
                            "Owner preview must not count as a viewer");

                    first.close();
                    await(
                            () -> !presence.readers(liveSession).containsKey(firstReader),
                            Duration.ofSeconds(55),
                            "Real UNREAD hook did not remove the old HLS session after inactivity");
                    refreshed.assertHealthy();
                    secondUser.assertHealthy();
                    assertTrue(
                            Instant.now().isAfter(shortToken.expiresAt()),
                            "Short Read JWT should have expired during the old-reader timeout");
                    assertTrue(
                            presence.readers(liveSession).containsKey(refreshedReader),
                            "Expired Read JWT must not remove an active HLS reader");
                    assertEquals(
                            2L,
                            metadataCount(liveSession, viewerA),
                            "Old reader timeout must preserve the refreshed viewer");

                    try (var connection = redis.getConnectionFactory().getConnection()) {
                        connection.serverCommands().flushDb();
                    }
                    assertNull(
                            metadataCount(liveSession, viewerA),
                            "Lost presence must not be reported as zero");
                    reconciliation.reconcile();
                    assertEquals(
                            2L,
                            metadataCount(liveSession, viewerA),
                            "Media observations must restore lost Redis presence");
                    assertEquals(
                            0,
                            REDIS.execInContainer("killall", "-KILL", "redis-server")
                                    .getExitCode());
                    await(
                            () -> {
                                try (var connection =
                                        redis.getConnectionFactory().getConnection()) {
                                    return "PONG".equals(connection.ping());
                                } catch (RuntimeException disconnected) {
                                    return false;
                                }
                            },
                            Duration.ofSeconds(15),
                            "Redis client must reconnect after the isolated server restarts");
                    assertNull(
                            metadataCount(liveSession, viewerA),
                            "Restarted Redis has not recovered presence yet");
                    reconciliation.reconcile();
                    assertEquals(
                            2L,
                            metadataCount(liveSession, viewerA),
                            "Redis restart must recover existing viewers without another READ");

                    UUID missedLeave = UUID.randomUUID();
                    presence.join(liveSession, UUID.randomUUID(), missedLeave);
                    assertEquals(3L, metadataCount(liveSession, viewerA));
                    reconciliation.reconcile();
                    assertEquals(
                            2L,
                            metadataCount(liveSession, viewerA),
                            "Confirmed missing reader must repair a lost UNREAD");
                    assertExpiredTokenCannotOpenNewSession(liveSession, shortToken.value());

                    secondUser.close();
                    kick(secondUserReader);
                    awaitCount(liveSession, viewerA, 1);
                    refreshed.close();
                    kick(refreshedReader);
                    awaitCount(liveSession, viewerA, 0);
                    assertTrue(
                            presence.readers(liveSession).isEmpty(),
                            "Owner/worker/relay readers must not enter the viewer index");
                    ownerPreview.assertHealthy();
                    worker.assertHealthy();
                }
            }

            try (var restartViewer =
                    open(liveSession, issuer.issueRead(liveSession, viewerA).value())) {
                awaitReader(liveSession, viewerA, 1);
                awaitCount(liveSession, viewerA, 1);
                assertEquals(
                        0, MEDIA.execInContainer("killall", "-KILL", "mediamtx").getExitCode());
                await(
                        () -> {
                            reconciliation.reconcile();
                            return Objects.equals(0L, presence.count(liveSession));
                        },
                        Duration.ofSeconds(20),
                        "Restarted MediaMTX must not leave ghost readers in Redis");
            }

            setupService.issueStreamKey(owner);
            sendLateRead(liveSession, viewerA);
            assertEquals(0L, metadataCount(liveSession, viewerA));
            assertEquals(
                    0L,
                    presence.count(liveSession),
                    "Late READ must not resurrect a closed stream");
        } catch (Exception | AssertionError failure) {
            System.err.println("Isolated media logs:\n" + MEDIA.getLogs());
            throw failure;
        } finally {
            publisher.destroy();
            if (!publisher.waitFor(3, TimeUnit.SECONDS)) {
                publisher.destroyForcibly();
            }
        }
    }

    private UUID awaitReader(UUID session, UUID user, int expected) {
        await(
                () ->
                        presence.readers(session).values().stream().filter(user::equals).count()
                                == expected,
                Duration.ofSeconds(15),
                "Real READ hook did not register expected reader membership");
        return presence.readers(session).entrySet().stream()
                .filter(entry -> entry.getValue().equals(user))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    private void awaitCount(UUID session, UUID user, long expected) {
        await(
                () -> Objects.equals(expected, metadataCount(session, user)),
                Duration.ofSeconds(15),
                "Metadata did not expose expected CCU " + expected);
    }

    private Long metadataCount(UUID session, UUID user) {
        try {
            var response =
                    http.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + STREAM_PORT
                                                            + "/stream/api/streams/"
                                                            + session))
                                    .header("Cookie", "access_token=" + accountCookie(user))
                                    .timeout(Duration.ofSeconds(5))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "Metadata request failed");
            var count = mapper.readTree(response.body()).path("result").path("currentViewerCount");
            return count.isNull() || count.isMissingNode() ? null : count.asLong();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot request isolated CCU metadata");
        }
    }

    private HlsViewer open(UUID session, String token) throws Exception {
        String url =
                "http://127.0.0.1:"
                        + MEDIA.getMappedPort(8888)
                        + "/live/"
                        + session
                        + "/index.m3u8?cookieCheck=1";
        return new HlsViewer(URI.create(url), token);
    }

    private MediaToken shortRead(UUID session, UUID user) {
        var shortProperties = new MediaProperties();
        shortProperties.setIssuer(properties.getIssuer());
        shortProperties.setAudience(properties.getAudience());
        shortProperties.setKeyId(properties.getKeyId());
        shortProperties.setReadTokenTtl(Duration.ofSeconds(4));
        return new MediaJwtIssuerImpl(keys, shortProperties, Clock.systemUTC())
                .issueRead(session, user);
    }

    private void assertExpiredTokenCannotOpenNewSession(UUID session, String token)
            throws Exception {
        URI url =
                URI.create(
                        "http://127.0.0.1:"
                                + MEDIA.getMappedPort(8888)
                                + "/live/"
                                + session
                                + "/index.m3u8?cookieCheck=1");
        var response = hlsRequest(url, token);
        assertTrue(
                response.statusCode() == 401 || response.statusCode() == 403,
                "An expired Read JWT must not open a new HLS session");
    }

    private void kick(UUID reader) throws Exception {
        URI url =
                URI.create(
                        "http://127.0.0.1:"
                                + MEDIA.getMappedPort(9997)
                                + "/v3/hls/sessions/kick/"
                                + reader);
        var response =
                http.send(
                        HttpRequest.newBuilder(url)
                                .header(
                                        "Authorization",
                                        "Bearer " + issuer.issueControlApi().value())
                                .timeout(Duration.ofSeconds(5))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode(), "Cannot kick the isolated HLS reader");
    }

    private void sendLateRead(UUID session, UUID user) throws Exception {
        var event =
                Map.of(
                        "eventId",
                        UUID.randomUUID(),
                        "eventType",
                        "READ",
                        "sessionId",
                        session,
                        "readerId",
                        UUID.randomUUID(),
                        "readerType",
                        "hlsSession",
                        "userId",
                        user,
                        "occurredAt",
                        Instant.now());
        var response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + STREAM_PORT
                                                        + "/stream/internal/api/viewer-events"))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(5))
                                .POST(
                                        HttpRequest.BodyPublishers.ofByteArray(
                                                mapper.writeValueAsBytes(event)))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode());
    }

    private byte[] credentialDigest() throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(TOKEN_DIRECTORY.resolve("control.jwt")));
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
                        "ccu-isolated-account-cookie-signing-key-32bytes"
                                .getBytes(StandardCharsets.UTF_8)));
        return token.serialize();
    }

    private Process publish(String fullRtmpUrl) throws IOException {
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
                        "180",
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

    private HttpResponse<String> hlsRequest(URI url, String token) throws Exception {
        return http.send(
                HttpRequest.newBuilder(url)
                        .header("Authorization", "Bearer " + token)
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void await(BooleanSupplier condition, Duration timeout, String description) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
        }
        fail(description);
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot reserve isolated CCU backend port");
        }
    }

    private static Path tokenDirectory() {
        try {
            return Files.createTempDirectory("stream-ccu-media-it-");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot prepare isolated CCU credential directory");
        }
    }

    private static String mediaConfiguration() {
        try {
            return Files.readString(REPOSITORY.resolve("media-server/config/mediamtx.yml"));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read pinned MediaMTX configuration");
        }
    }

    private static byte[] hookScript(String name) {
        try {
            return Files.readAllBytes(REPOSITORY.resolve("media-server/hooks/" + name));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read media hook script " + name, exception);
        }
    }

    private final class HlsViewer implements AutoCloseable {
        private final URI playlist;
        private final String token;
        private final ScheduledExecutorService requests =
                Executors.newSingleThreadScheduledExecutor(
                        task -> {
                            Thread thread = new Thread(task, "ccu-hls-reader-test");
                            thread.setDaemon(true);
                            return thread;
                        });
        private final AtomicReference<String> failure = new AtomicReference<>();

        private HlsViewer(URI masterUrl, String token) throws Exception {
            this.token = token;
            HttpResponse<String> master = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                master = hlsRequest(masterUrl, token);
                if (master.statusCode() == 200) {
                    break;
                }
                LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
            }
            assertEquals(200, master.statusCode(), "Cannot open isolated HLS session");
            String variant =
                    master.body()
                            .lines()
                            .map(String::strip)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .findFirst()
                            .orElseThrow();
            playlist = master.uri().resolve(variant);
            assertEquals(
                    masterUrl.getAuthority(),
                    playlist.getAuthority(),
                    "HLS playlist must remain on the isolated media origin");
            assertEquals(
                    200,
                    hlsRequest(playlist, token).statusCode(),
                    "Cannot load isolated HLS rendition");
            requests.scheduleWithFixedDelay(this::keepAlive, 0, 1, TimeUnit.SECONDS);
        }

        private void keepAlive() {
            try {
                int status = hlsRequest(playlist, token).statusCode();
                if (status != 200)
                    failure.compareAndSet(null, "HLS keepalive rejected with status " + status);
            } catch (Exception exception) {
                if (!requests.isShutdown()) failure.compareAndSet(null, "HLS keepalive failed");
            }
        }

        private void assertHealthy() {
            assertNull(failure.get());
        }

        @Override
        public void close() throws InterruptedException {
            requests.shutdownNow();
            requests.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
