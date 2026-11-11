package com.reallive.streamservice.stream.adapter.media;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.common.configuration.MediaKeyMaterial;
import com.reallive.streamservice.common.configuration.MediaProperties;
import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.adapter.media.impl.MediaJwtIssuerImpl;
import com.reallive.streamservice.stream.adapter.redis.StreamViewerPresenceStore;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import com.reallive.streamservice.stream.scheduler.CcuReconciliationScheduler;
import com.reallive.streamservice.stream.service.StreamSessionClosureService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "stream.ccu.it", matches = "true")
class StreamViewerPresenceMediaIT extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final int STREAM_PORT = freePort();
    private static final String CALLBACK_TOKEN = "ccu-media-integration-callback-token-0123456789";
    private static final Path TOKEN_DIRECTORY = tokenDirectory();
    private static final Path REPOSITORY = Files.exists(Path.of("media-server/config/mediamtx.yml"))
            ? Path.of(".").toAbsolutePath() : Path.of("..").toAbsolutePath();

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withCreateContainerCmdModifier(command -> command.withEntrypoint(
                    "/bin/sh", "-c", "while true; do redis-server --save '' --appendonly no; done"))
            .withExposedPorts(6379);

    @Container
    private static final GenericContainer<?> MEDIA = new GenericContainer<>(DockerImageName.parse(
            System.getProperty("stream.media.test-image", "reallive-media-server:1.21.2")))
            .withCreateContainerCmdModifier(command -> command.withEntrypoint(
                    "/bin/sh", "-c", "while true; do /mediamtx /mediamtx.yml; done"))
            .withCopyToContainer(Transferable.of(mediaConfiguration()), "/mediamtx.yml")
            .withFileSystemBind(TOKEN_DIRECTORY.toString(), "/run/media-hook", BindMode.READ_ONLY)
            .withExtraHost("host.docker.internal", "host-gateway")
            .withEnv("MTX_AUTHJWTJWKS", "http://host.docker.internal:" + STREAM_PORT + "/stream/api/media/jwks")
            .withEnv("STREAM_MEDIA_CALLBACK_URL", "http://host.docker.internal:" + STREAM_PORT + "/stream/api/media/events")
            .withEnv("STREAM_CCU_ENABLED", "true")
            .withEnv("STREAM_CCU_CALLBACK_TOKEN", CALLBACK_TOKEN)
            .withEnv("STREAM_VIEWER_CALLBACK_URL", "http://host.docker.internal:" + STREAM_PORT + "/stream/internal/api/viewer-events")
            .withEnv("MEDIA_CONTROL_TOKEN_FILE", "/run/media-hook/control.jwt")
            .withExposedPorts(1935, 8888, 9997);

    @Autowired private StreamSessionRepository sessions;
    @Autowired private StreamSessionClosureService closure;
    @Autowired private MediaServerAdapter media;
    @Autowired private MediaJwtIssuer issuer;
    @Autowired private MediaKeyMaterial keys;
    @Autowired private MediaProperties properties;
    @Autowired private StreamViewerPresenceStore presence;
    @Autowired private CcuReconciliationScheduler reconciliation;
    @Autowired private CcuControlTokenPublisher tokenPublisher;
    @Autowired private ObjectMapper mapper;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redis;
    @MockitoBean private RedisAuthoritiesReader authorities;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        configureDatabase(registry, SCHEMA);
        registry.add("server.port", () -> STREAM_PORT);
        registry.add("server.servlet.context-path", () -> "/stream");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("stream.ccu.enabled", () -> "true");
        registry.add("stream.ccu.callback-token", () -> CALLBACK_TOKEN);
        registry.add("stream.ccu.control-token-directory", TOKEN_DIRECTORY::toString);
        registry.add("stream.ccu.reconcile-delay", () -> "3600000");
        registry.add("stream.ccu.token-publish-delay", () -> "3600000");
        registry.add("stream.media.control-token-ttl", () -> "2h");
        registry.add("stream.media.control-api.base-url", () -> "http://127.0.0.1:" + MEDIA.getMappedPort(9997));
        registry.add("stream.media.rtmp-public-url", () -> "rtmp://127.0.0.1:" + MEDIA.getMappedPort(1935) + "/live");
        registry.add("stream.media.hls-public-base-url", () -> "http://127.0.0.1:" + MEDIA.getMappedPort(8888));
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
    void realHooksDeduplicateAccountsSurviveRefreshAndExpiryAndRemoveOnlyTheLastReader() throws Exception {
        when(authorities.read(any())).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
        UUID owner = UUID.randomUUID();
        UUID viewerA = UUID.randomUUID();
        UUID viewerB = UUID.randomUUID();
        UUID session = createReadySession(owner);
        assertEquals(0L, metadataCount(session, viewerA));
        media.ensurePath(session);
        Process publisher = publish(session, owner);
        try {
            await(() -> publisher.isAlive() && sessions.findById(session).orElseThrow().getStatus() == StreamSessionStatus.LIVE,
                    Duration.ofSeconds(25), "Real ONLINE hook did not move the isolated stream to LIVE");
            await(() -> {
                reconciliation.reconcile();
                return Objects.equals(0L, presence.count(session));
            }, Duration.ofSeconds(10), "Empty viewer presence did not become ready");
            assertTrue(Files.isRegularFile(TOKEN_DIRECTORY.resolve("control.jwt")), "Hook credential must be published before viewing");

            try (var ownerPreview = open(session, issuer.issueRead(session, owner).value());
                 var worker = open(session, issuer.issueWorkerRead(session).value());
                 var first = open(session, issuer.issueRead(session, viewerA).value())) {
                UUID firstReader = awaitReader(session, viewerA, 1);
                awaitCount(session, viewerA, 1);

                byte[] before = credentialDigest();
                tokenPublisher.publish();
                assertFalse(Arrays.equals(before, credentialDigest()), "Publishing must replace the hook credential");
                var shortToken = shortRead(session, viewerA);
                try (var refreshed = open(session, shortToken.value());
                     var secondUser = open(session, issuer.issueRead(session, viewerB).value())) {
                    awaitReader(session, viewerA, 2);
                    UUID refreshedReader = presence.readers(session).entrySet().stream()
                            .filter(entry -> entry.getValue().equals(viewerA) && !entry.getKey().equals(firstReader))
                            .map(Map.Entry::getKey).findFirst().orElseThrow();
                    UUID secondUserReader = awaitReader(session, viewerB, 1);
                    awaitCount(session, viewerA, 2);
                    assertFalse(presence.readers(session).containsValue(owner), "Owner preview must not count as a viewer");

                    first.close();
                    await(() -> !presence.readers(session).containsKey(firstReader), Duration.ofSeconds(55),
                            "Real UNREAD hook did not remove the old HLS session after inactivity");
                    refreshed.assertHealthy();
                    secondUser.assertHealthy();
                    assertTrue(Instant.now().isAfter(shortToken.expiresAt()), "Short Read JWT should have expired during the old-reader timeout");
                    assertTrue(presence.readers(session).containsKey(refreshedReader), "Expired Read JWT must not remove an active HLS reader");
                    assertEquals(2L, metadataCount(session, viewerA), "Old reader timeout must preserve the refreshed viewer");
                    // This template connects only to the isolated Redis Testcontainer.
                    try (var connection = redis.getConnectionFactory().getConnection()) {
                        connection.serverCommands().flushDb();
                    }
                    assertNull(metadataCount(session, viewerA), "Lost presence must not be reported as zero");
                    reconciliation.reconcile();
                    assertEquals(2L, metadataCount(session, viewerA), "Media observations must restore lost Redis presence");
                    assertEquals(0, REDIS.execInContainer("killall", "-KILL", "redis-server").getExitCode());
                    await(() -> {
                        try (var connection = redis.getConnectionFactory().getConnection()) {
                            return "PONG".equals(connection.ping());
                        } catch (RuntimeException disconnected) {
                            return false;
                        }
                    }, Duration.ofSeconds(15), "Redis client must reconnect after the isolated server restarts");
                    assertNull(metadataCount(session, viewerA), "Restarted Redis has not recovered presence yet");
                    reconciliation.reconcile();
                    assertEquals(2L, metadataCount(session, viewerA), "Redis restart must recover existing viewers without another READ");
                    UUID missedLeave = UUID.randomUUID();
                    presence.join(session, UUID.randomUUID(), missedLeave);
                    assertEquals(3L, metadataCount(session, viewerA));
                    reconciliation.reconcile();
                    assertEquals(2L, metadataCount(session, viewerA), "Confirmed missing reader must repair a lost UNREAD");
                    assertExpiredTokenCannotOpenNewSession(session, shortToken.value());

                    secondUser.close();
                    kick(secondUserReader);
                    awaitCount(session, viewerA, 1);
                    refreshed.close();
                    kick(refreshedReader);
                    awaitCount(session, viewerA, 0);
                    assertTrue(presence.readers(session).isEmpty(), "Owner/worker readers must not enter the viewer index");
                    ownerPreview.assertHealthy();
                    worker.assertHealthy();
                }
            }

            try (var restartViewer = open(session, issuer.issueRead(session, viewerA).value())) {
                awaitReader(session, viewerA, 1);
                awaitCount(session, viewerA, 1);
                // Restart the media process inside its isolated supervisor to keep mapped ports stable.
                // SIGKILL deliberately prevents shutdown hooks: recovery must remove ghost readers.
                assertEquals(0, MEDIA.execInContainer("killall", "-KILL", "mediamtx").getExitCode());
                await(() -> {
                    reconciliation.reconcile();
                    return Objects.equals(0L, presence.count(session));
                }, Duration.ofSeconds(20), "Restarted MediaMTX must not leave ghost readers in Redis");
            }
            assertEquals(StreamSessionStatus.ENDED, closure.end(session, owner, "CCU isolated E2E complete").status());
            sendLateRead(session, viewerA);
            assertEquals(0L, metadataCount(session, viewerA));
            assertEquals(0L, presence.count(session), "Late READ must not resurrect a closed stream");
        } catch (Exception | AssertionError failure) {
            System.err.println("Isolated media logs:\n" + MEDIA.getLogs());
            throw failure;
        } finally {
            try {
                closure.end(session, owner, "CCU isolated E2E cleanup");
            } finally {
                publisher.destroy();
                if (!publisher.waitFor(3, TimeUnit.SECONDS)) publisher.destroyForcibly();
                try { media.deletePath(session); } catch (RuntimeException ignored) {
                    System.err.println("Isolated media path cleanup unavailable; container teardown will remove it");
                }
            }
        }
    }

    private UUID createReadySession(UUID owner) {
        UUID session = UUID.randomUUID();
        sessions.saveAndFlush(StreamSessionEntity.builder()
                .id(session).ownerUserId(owner).streamerId(UUID.randomUUID()).channelId(UUID.randomUUID())
                .categoryId(UUID.randomUUID()).creationRequestId(UUID.randomUUID()).title("Isolated CCU test")
                .visibility(StreamVisibility.PUBLIC).status(StreamSessionStatus.READY)
                .mediaPathState(MediaPathState.OPEN).playbackPath("/live/" + session + "/index.m3u8").build());
        return session;
    }

    private UUID awaitReader(UUID session, UUID user, int expected) {
        await(() -> presence.readers(session).values().stream().filter(user::equals).count() == expected,
                Duration.ofSeconds(15), "Real READ hook did not register expected reader membership");
        return presence.readers(session).entrySet().stream().filter(entry -> entry.getValue().equals(user))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private void awaitCount(UUID session, UUID user, long expected) {
        await(() -> Objects.equals(expected, metadataCount(session, user)), Duration.ofSeconds(15),
                "Metadata did not expose expected CCU " + expected);
    }

    private Long metadataCount(UUID session, UUID user) {
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + STREAM_PORT + "/stream/api/streams/" + session))
                    .header("Cookie", "access_token=" + accountCookie(user)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "Metadata request failed");
            var count = mapper.readTree(response.body()).path("result").path("currentViewerCount");
            return count.isNull() || count.isMissingNode() ? null : count.asLong();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot request isolated CCU metadata");
        }
    }

    private HlsViewer open(UUID session, String token) throws Exception {
        String url = "http://127.0.0.1:" + MEDIA.getMappedPort(8888) + "/live/" + session + "/index.m3u8?cookieCheck=1";
        return new HlsViewer(URI.create(url), token);
    }

    private MediaToken shortRead(UUID session, UUID user) {
        var shortProperties = new MediaProperties();
        shortProperties.setIssuer(properties.getIssuer());
        shortProperties.setAudience(properties.getAudience());
        shortProperties.setKeyId(properties.getKeyId());
        shortProperties.setReadTokenTtl(Duration.ofSeconds(4));
        return new MediaJwtIssuerImpl(keys, shortProperties, Clock.systemUTC()).issueRead(session, user);
    }

    private void assertExpiredTokenCannotOpenNewSession(UUID session, String token) throws Exception {
        URI url = URI.create("http://127.0.0.1:" + MEDIA.getMappedPort(8888) + "/live/" + session + "/index.m3u8?cookieCheck=1");
        var response = hlsRequest(url, token);
        assertTrue(response.statusCode() == 401 || response.statusCode() == 403,
                "An expired Read JWT must not open a new HLS session");
    }

    private void kick(UUID reader) throws Exception {
        URI url = URI.create("http://127.0.0.1:" + MEDIA.getMappedPort(9997) + "/v3/hls/sessions/kick/" + reader);
        var response = http.send(HttpRequest.newBuilder(url).header("Authorization", "Bearer " + issuer.issueControlApi().value())
                .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode(), "Cannot kick the isolated HLS reader");
    }

    private void sendLateRead(UUID session, UUID user) throws Exception {
        var event = Map.of("eventId", UUID.randomUUID(), "eventType", "READ", "sessionId", session,
                "readerId", UUID.randomUUID(), "readerType", "hlsSession", "userId", user, "occurredAt", Instant.now());
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + STREAM_PORT + "/stream/internal/api/viewer-events"))
                .header("X-Internal-Token", CALLBACK_TOKEN).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(event))).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode());
    }

    private byte[] credentialDigest() throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(TOKEN_DIRECTORY.resolve("control.jwt")));
    }

    private String accountCookie(UUID user) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(user.toString()).issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900))).claim("token_type", "ACCESS").build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        token.sign(new MACSigner("ccu-isolated-account-cookie-signing-key-32bytes".getBytes(StandardCharsets.UTF_8)));
        return token.serialize();
    }

    private Process publish(UUID session, UUID owner) throws IOException {
        var credentials = issuer.publishCredentials(session, owner);
        return new ProcessBuilder("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-re", "-f", "lavfi", "-i",
                "testsrc=size=160x90:rate=10", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "180", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-g", "20",
                "-c:a", "aac", "-f", "flv", credentials.serverUrl() + "/" + credentials.streamKey())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private HttpResponse<String> hlsRequest(URI url, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(url).header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
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

    private final class HlsViewer implements AutoCloseable {
        private final URI playlist;
        private final String token;
        private final ScheduledExecutorService requests = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ccu-hls-reader-test");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicReference<String> failure = new AtomicReference<>();

        private HlsViewer(URI masterUrl, String token) throws Exception {
            this.token = token;
            var master = hlsRequest(masterUrl, token);
            assertEquals(200, master.statusCode(), "Cannot open isolated HLS session");
            String variant = master.body().lines().map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#")).findFirst().orElseThrow();
            playlist = master.uri().resolve(variant);
            assertEquals(masterUrl.getAuthority(), playlist.getAuthority(), "HLS playlist must remain on the isolated media origin");
            assertEquals(200, hlsRequest(playlist, token).statusCode(), "Cannot load isolated HLS rendition");
            requests.scheduleWithFixedDelay(this::keepAlive, 0, 1, TimeUnit.SECONDS);
        }

        private void keepAlive() {
            try {
                int status = hlsRequest(playlist, token).statusCode();
                if (status != 200) failure.compareAndSet(null, "HLS keepalive rejected with status " + status);
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
