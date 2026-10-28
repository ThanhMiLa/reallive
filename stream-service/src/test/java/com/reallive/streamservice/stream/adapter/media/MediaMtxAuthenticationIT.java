package com.reallive.streamservice.stream.adapter.media;

import com.reallive.streamservice.common.configuration.MediaKeyMaterial;
import com.reallive.streamservice.common.configuration.MediaProperties;
import com.reallive.streamservice.stream.MediaTestSupport;
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

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NORMAL).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MediaTestSupport.register(registry);
    }

    @Test
    void javaTokensAuthenticateAgainstMediaMtxUsingDirectStreamServiceJwks() throws Exception {
        Path repository = Files.exists(Path.of("mediamtx/mediamtx.yml"))
                ? Path.of(".").toAbsolutePath() : Path.of("..").toAbsolutePath();
        String jwksUrl = "http://127.0.0.1:" + streamPort + "/stream/api/media/jwks";
        var jwks = mapper.readTree(request("GET", jwksUrl, null, null).body());
        assertEquals("RSA", jwks.path("keys").get(0).path("kty").asString());
        assertFalse(jwks.path("keys").get(0).has("d"));

        byte[] event = mapper.writeValueAsBytes(Map.of("eventId", UUID.randomUUID(), "eventType", "ONLINE",
                "path", "live/" + UUID.randomUUID(), "sourceId", UUID.randomUUID(), "sourceType", "rtmpConn",
                "occurredAt", Instant.now()));
        String callbackUrl = "http://127.0.0.1:" + streamPort + "/stream/api/media/events";
        var callbackRequest = HttpRequest.newBuilder(URI.create(callbackUrl)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(event)).timeout(Duration.ofSeconds(5)).build();
        assertEquals(404, http.send(callbackRequest, HttpResponse.BodyHandlers.ofString()).statusCode(),
                "Direct callback for unknown session must reach stream-service and return 404");

        String config = Files.readString(repository.resolve("mediamtx/mediamtx.yml"))
                .replaceAll("(?m)^\\s*runOn(?:Online|Offline):[^\\n]*\\n", "");
        try (var media = new GenericContainer<>(DockerImageName.parse("reallive-mediamtx:1.21.2"))
                .withCopyToContainer(Transferable.of(config), "/mediamtx.yml")
                .withExtraHost("host.docker.internal", "host-gateway")
                .withEnv("MTX_AUTHJWTJWKS", "http://host.docker.internal:" + streamPort + "/stream/api/media/jwks")
                .withExposedPorts(1935, 8888, 9997)) {
            media.start();
            verifyMedia(media);
        }
    }

    private void verifyMedia(GenericContainer<?> media) throws Exception {
        String api = "http://127.0.0.1:" + media.getMappedPort(9997);
        String hls = "http://127.0.0.1:" + media.getMappedPort(8888);
        String rtmp = "rtmp://127.0.0.1:" + media.getMappedPort(1935) + "/live/";
        String control = issuer.issueControlApi().value();
        UUID session = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        String path = "live/" + session;
        assertEquals(200, request("GET", api + "/v3/config/paths/list", control, null).statusCode());
        assertDenied(request("GET", api + "/v3/config/paths/list", issuer.issuePublish(session, owner).value(), null).statusCode());
        assertDenied(request("GET", api + "/v3/config/paths/list", issuer.issueRead(session, owner).value(), null).statusCode());
        assertEquals(200, request("POST", api + "/v3/config/paths/add/" + path, control, "{}").statusCode());
        Process publisher = publish(rtmp + session + "?token=" + issuer.issuePublish(session, owner).value(), 45);
        try {
            awaitSource(api, path, control, publisher);
            String playback = hls + "/" + path + "/index.m3u8";
            var allowed = request("GET", playback, issuer.issueRead(session, owner).value(), null);
            assertEquals(200, allowed.statusCode());
            assertTrue(allowed.body().contains("#EXTM3U"));
            var oldIssuer = new MediaJwtIssuer(keys, properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-1)));
            var otherKeys = MediaTestSupport.generateKeys();
            var wrongSigner = new MediaJwtIssuer(new MediaKeyMaterial(
                    (RSAPrivateKey) otherKeys.getPrivate(),
                    (RSAPublicKey) otherKeys.getPublic()), properties, Clock.systemUTC());
            for (String token : List.of("", issuer.issuePublish(session, owner).value(),
                    issuer.issueRead(UUID.randomUUID(), owner).value(), oldIssuer.issueRead(session, owner).value(),
                    wrongSigner.issueRead(session, owner).value())) {
                assertDenied(request("GET", playback, token.isEmpty() ? null : token, null).statusCode());
            }
            String original = properties.getIssuer();
            try {
                properties.setIssuer("wrong-issuer");
                assertDenied(request("GET", playback, issuer.issueRead(session, owner).value(), null).statusCode());
            } finally { properties.setIssuer(original); }
            original = properties.getAudience();
            try {
                properties.setAudience("wrong-audience");
                assertDenied(request("GET", playback, issuer.issueRead(session, owner).value(), null).statusCode());
            } finally { properties.setAudience(original); }
        } finally {
            publisher.destroy();
            if (!publisher.waitFor(3, TimeUnit.SECONDS)) { publisher.destroyForcibly(); }
            request("DELETE", api + "/v3/config/paths/delete/" + path, control, null);
        }
        UUID deniedSession = UUID.randomUUID();
        String deniedPath = "live/" + deniedSession;
        assertEquals(200, request("POST", api + "/v3/config/paths/add/" + deniedPath, control, "{}").statusCode());
        try {
            Process denied = publish(rtmp + deniedSession + "?token=" + issuer.issueRead(deniedSession, owner).value(), 8);
            try {
                assertTrue(denied.waitFor(15, TimeUnit.SECONDS), "Invalid publisher did not terminate");
                assertNotEquals(0, denied.exitValue(), "Read JWT must not publish");
                var observed = request("GET", api + "/v3/paths/get/" + deniedPath, control, null);
                assertTrue(observed.statusCode() == 404 || mapper.readTree(observed.body()).path("source").isNull());
            } finally { denied.destroyForcibly(); }
        } finally { request("DELETE", api + "/v3/config/paths/delete/" + deniedPath, control, null); }
    }

    private Process publish(String url, int duration) throws Exception {
        return new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-re", "-f", "lavfi", "-i",
                "testsrc=size=160x90:rate=10", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", Integer.toString(duration), "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-g", "20", "-c:a", "aac", "-f", "flv", url)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private HttpResponse<String> request(String method, String url, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void awaitSource(String api, String path, String control, Process publisher) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(publisher.isAlive(), "Publisher exited before source became online");
            var response = request("GET", api + "/v3/paths/get/" + path, control, null);
            if (response.statusCode() == 200 && mapper.readTree(response.body()).path("source").isObject()) { return; }
            LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
        }
        fail("Media source did not become online");
    }

    private void assertDenied(int status) {
        assertTrue(status == 401 || status == 403, "Expected authentication denial, received " + status);
    }
}
