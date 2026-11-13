package com.reallive.streamservice.stream.adapter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.common.configuration.MediaKeyMaterial;
import com.reallive.streamservice.common.configuration.MediaProperties;
import com.reallive.streamservice.stream.MediaTestSupport;
import com.reallive.streamservice.stream.adapter.media.impl.MediaJwtIssuerImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.servlet.context-path=/stream",
            "spring.datasource.url=jdbc:h2:mem:media_auth_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "stream.media.it", matches = "true")
class MediaMtxAuthenticationIT {

    @LocalServerPort private int streamPort;
    @Autowired private MediaJwtIssuer issuer;
    @Autowired private MediaProperties properties;
    @Autowired private MediaKeyMaterial keys;
    @Autowired private ObjectMapper mapper;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MediaTestSupport.register(registry);
    }

    @Test
    void javaTokensAuthenticateAgainstMediaMtxWithTwoPathIsolationAndJwks() throws Exception {
        Path repository =
                Files.exists(Path.of("media-server/config/mediamtx.yml"))
                                || Files.exists(Path.of("media-server/mediamtx.yml"))
                        ? Path.of(".").toAbsolutePath()
                        : Path.of("..").toAbsolutePath();

        String jwksUrl = "http://127.0.0.1:" + streamPort + "/stream/api/media/jwks";
        var jwks = mapper.readTree(request("GET", jwksUrl, null, null).body());
        assertEquals("RSA", jwks.path("keys").get(0).path("kty").asString());
        assertFalse(jwks.path("keys").get(0).has("d"));

        byte[] event =
                mapper.writeValueAsBytes(
                        Map.of(
                                "eventId",
                                UUID.randomUUID(),
                                "eventType",
                                "ONLINE",
                                "path",
                                "ingest/" + UUID.randomUUID(),
                                "sourceId",
                                UUID.randomUUID(),
                                "sourceType",
                                "rtmpConn",
                                "occurredAt",
                                Instant.now()));
        String callbackUrl = "http://127.0.0.1:" + streamPort + "/stream/internal/api/media-events";
        assertEquals(
                401,
                http.send(
                                HttpRequest.newBuilder(URI.create(callbackUrl))
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofByteArray(event))
                                        .timeout(Duration.ofSeconds(5))
                                        .build(),
                                HttpResponse.BodyHandlers.discarding())
                        .statusCode());
        HttpResponse<String> callbackResponse =
                http.send(
                        HttpRequest.newBuilder(URI.create(callbackUrl))
                                .header("Content-Type", "application/json")
                                .header(
                                        "X-Internal-Token",
                                        MediaTestSupport.CALLBACK_INTERNAL_TOKEN)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(event))
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, callbackResponse.statusCode());
        assertEquals("IGNORED", mapper.readTree(callbackResponse.body()).path("result").path("outcome").asString());

        Path configPath =
                Files.exists(repository.resolve("media-server/config/mediamtx.yml"))
                        ? repository.resolve("media-server/config/mediamtx.yml")
                        : repository.resolve("media-server/mediamtx.yml");
        String config =
                Files.readString(configPath)
                        .replaceAll("(?m)^\\s*runOn(?:Online|Offline):[^\\n]*\\n", "");

        try (var media =
                new GenericContainer<>(
                                DockerImageName.parse(
                                        System.getProperty(
                                                "stream.media.test-image",
                                                "reallive-media-server:1.21.2")))
                        .withCopyToContainer(Transferable.of(config), "/mediamtx.yml")
                        .withExtraHost("host.docker.internal", "host-gateway")
                        .withEnv(
                                "MTX_AUTHJWTJWKS",
                                "http://host.docker.internal:"
                                        + streamPort
                                        + "/stream/api/media/jwks")
                        .withExposedPorts(1935, 8888, 9997)) {
            media.start();
            verifyTwoPathMediaAuth(media);
        }
    }

    private void verifyTwoPathMediaAuth(GenericContainer<?> media) throws Exception {
        String api = "http://127.0.0.1:" + media.getMappedPort(9997);
        String hls = "http://127.0.0.1:" + media.getMappedPort(8888);
        String rtmpBase = "rtmp://127.0.0.1:" + media.getMappedPort(1935) + "/";
        String control = issuer.issueControlApi().value();

        UUID ingestKeyId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        String ingestPath = "ingest/" + ingestKeyId;
        String playbackPath = "live/" + sessionId;

        MediaToken publishToken = issuer.issuePublish(ingestKeyId, owner);
        MediaToken relayReadToken = issuer.issueRelayRead(ingestKeyId);
        assertNull(publishToken.expiresAt());
        assertNull(relayReadToken.expiresAt());
        assertNull(SignedJWT.parse(publishToken.value()).getJWTClaimsSet().getExpirationTime());
        assertNull(SignedJWT.parse(relayReadToken.value()).getJWTClaimsSet().getExpirationTime());

        assertEquals(
                200, request("GET", api + "/v3/config/paths/list", control, null).statusCode());
        assertDenied(
                request("GET", api + "/v3/config/paths/list", publishToken.value(), null)
                        .statusCode());
        assertDenied(
                request(
                                "GET",
                                api + "/v3/config/paths/list",
                                issuer.issueRead(sessionId, owner).value(),
                                null)
                        .statusCode());

        assertEquals(
                200,
                request(
                                "POST",
                                api + "/v3/config/paths/add/" + ingestPath,
                                control,
                                "{\"source\":\"publisher\",\"overridePublisher\":false,\"runOnRead\":\"\",\"runOnUnread\":\"\"}")
                        .statusCode());
        String relayUrl =
                "rtmp://127.0.0.1:1935/" + ingestPath + "?token=" + relayReadToken.value();
        assertEquals(
                200,
                request(
                                "POST",
                                api + "/v3/config/paths/add/" + playbackPath,
                                control,
                                mapper.writeValueAsString(
                                        Map.of("source", relayUrl, "sourceOnDemand", false)))
                        .statusCode());

        Process publisher = publish(rtmpBase + ingestPath + "?token=" + publishToken.value(), 45);
        try {
            awaitSource(api, ingestPath, control, publisher);
            awaitSource(api, playbackPath, control, publisher);

            String playbackHls = hls + "/" + playbackPath + "/index.m3u8";
            String ingestHls = hls + "/" + ingestPath + "/index.m3u8";
            String viewerReadJwt = issuer.issueRead(sessionId, owner).value();
            var allowed = awaitHls(playbackHls, viewerReadJwt, publisher);
            assertEquals(200, allowed.statusCode());
            assertTrue(allowed.body().contains("#EXTM3U"));

            // Viewer Read JWT must NOT read ingest/*
            assertDenied(request("GET", ingestHls, viewerReadJwt, null).statusCode());

            var oldIssuer =
                    new MediaJwtIssuerImpl(
                            keys, properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-1)));
            var otherKeys = MediaTestSupport.generateKeys();
            var wrongSigner =
                    new MediaJwtIssuerImpl(
                            new MediaKeyMaterial(
                                    (RSAPrivateKey) otherKeys.getPrivate(),
                                    (RSAPublicKey) otherKeys.getPublic()),
                            properties,
                            Clock.systemUTC());

            for (String token :
                    List.of(
                            "",
                            publishToken.value(),
                            relayReadToken.value(),
                            issuer.issueRead(UUID.randomUUID(), owner).value(),
                            oldIssuer.issueRead(sessionId, owner).value(),
                            wrongSigner.issueRead(sessionId, owner).value())) {
                assertDenied(
                        request("GET", playbackHls, token.isEmpty() ? null : token, null)
                                .statusCode());
            }

            String originalIssuer = properties.getIssuer();
            try {
                properties.setIssuer("wrong-issuer");
                assertDenied(
                        request(
                                        "GET",
                                        playbackHls,
                                        issuer.issueRead(sessionId, owner).value(),
                                        null)
                                .statusCode());
            } finally {
                properties.setIssuer(originalIssuer);
            }

            String originalAudience = properties.getAudience();
            try {
                properties.setAudience("wrong-audience");
                assertDenied(
                        request(
                                        "GET",
                                        playbackHls,
                                        issuer.issueRead(sessionId, owner).value(),
                                        null)
                                .statusCode());
            } finally {
                properties.setAudience(originalAudience);
            }

            // Publish JWT cannot publish directly to live/{sessionId}
            Process directPlaybackDenied =
                    publish(rtmpBase + playbackPath + "?token=" + publishToken.value(), 8);
            try {
                assertTrue(directPlaybackDenied.waitFor(15, TimeUnit.SECONDS));
                assertNotEquals(0, directPlaybackDenied.exitValue());
            } finally {
                directPlaybackDenied.destroyForcibly();
            }
        } finally {
            publisher.destroy();
            if (!publisher.waitFor(3, TimeUnit.SECONDS)) {
                publisher.destroyForcibly();
            }
            request("DELETE", api + "/v3/config/paths/delete/" + playbackPath, control, null);
            request("DELETE", api + "/v3/config/paths/delete/" + ingestPath, control, null);
        }

        // Viewer Read JWT cannot publish to ingest/*
        UUID deniedIngest = UUID.randomUUID();
        String deniedIngestPath = "ingest/" + deniedIngest;
        assertEquals(
                200,
                request(
                                "POST",
                                api + "/v3/config/paths/add/" + deniedIngestPath,
                                control,
                                "{\"source\":\"publisher\"}")
                        .statusCode());
        try {
            Process denied =
                    publish(
                            rtmpBase
                                    + deniedIngestPath
                                    + "?token="
                                    + issuer.issueRead(sessionId, owner).value(),
                            8);
            try {
                assertTrue(
                        denied.waitFor(15, TimeUnit.SECONDS),
                        "Invalid publisher did not terminate");
                assertNotEquals(0, denied.exitValue(), "Read JWT must not publish");
                var observed =
                        request("GET", api + "/v3/paths/get/" + deniedIngestPath, control, null);
                assertTrue(
                        observed.statusCode() == 404
                                || mapper.readTree(observed.body()).path("source").isNull());
            } finally {
                denied.destroyForcibly();
            }
        } finally {
            request("DELETE", api + "/v3/config/paths/delete/" + deniedIngestPath, control, null);
        }
    }

    private Process publish(String url, int duration) throws Exception {
        return new ProcessBuilder(
                        "ffmpeg",
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
                        Integer.toString(duration),
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
                        url)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private HttpResponse<String> request(String method, String url, String token, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return http.send(
                builder.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void awaitSource(String api, String path, String control, Process publisher)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(
                    publisher.isAlive(), "Publisher exited before source became online on " + path);
            var response = request("GET", api + "/v3/paths/get/" + path, control, null);
            if (response.statusCode() == 200
                    && mapper.readTree(response.body()).path("source").isObject()) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
        }
        fail("Media source did not become online on " + path);
    }

    private HttpResponse<String> awaitHls(String url, String token, Process publisher)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        HttpResponse<String> last = null;
        while (System.nanoTime() < deadline) {
            assertTrue(
                    publisher.isAlive(),
                    "Publisher exited before HLS playlist became available at " + url);
            last = request("GET", url, token, null);
            if (last.statusCode() == 200 && last.body().contains("#EXTM3U")) {
                return last;
            }
            LockSupport.parkNanos(Duration.ofMillis(150).toNanos());
        }
        return last;
    }

    private void assertDenied(int status) {
        assertTrue(
                status == 401 || status == 403,
                "Expected authentication denial, received " + status);
    }
}
