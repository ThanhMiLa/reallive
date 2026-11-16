package com.reallive.channelservice.channel.client.user;

import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import feign.FeignException;
import org.mockito.Mockito;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:profile_client;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class UserProfileClientIntegrationTest {
    static final AtomicReference<String> body = new AtomicReference<>();
    static final AtomicReference<String> path = new AtomicReference<>();
    static final AtomicReference<String> method = new AtomicReference<>();
    static final AtomicReference<String> authorization = new AtomicReference<>();
    static volatile String reply = "{\"status\":200,\"code\":1000,\"result\":{\"displayName\":\"Updated\",\"avatarUrl\":\"https://example.com/new.png\"}}";
    static volatile int status = 200;
    static final HttpServer server = start();

    @Autowired UserProfileClient client;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.service.user", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll
    static void close() { server.stop(0); }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    void encodesOptionalMultipartPartsOverRealPatch(boolean name, boolean avatar) {
        status = 200;
        UUID userId = UUID.randomUUID();
        var file = avatar ? new MockMultipartFile("avatar", "face.png", "image/png", "image bytes".getBytes(StandardCharsets.UTF_8)) : null;
        var result = client.update(userId, name ? "Updated" : null, file);
        assertEquals("Updated", result.displayName());
        assertEquals("PATCH", method.get());
        assertEquals("/internal/api/users/" + userId + "/channel-profile", path.get());
        assertNull(authorization.get());
        assertEquals(name, body.get().contains("name=\"displayName\""));
        assertEquals(avatar, body.get().contains("name=\"avatar\""));
        if (avatar) assertTrue(body.get().contains("image bytes"));
    }

    @Test
    void rejectsFailedAndMalformedUpstreamResponses() {
        String validReply = reply;
        try {
            for (int httpStatus : new int[]{400, 404, 503}) {
                status = httpStatus;
                AppException error = assertThrows(AppException.class, () -> client.update(UUID.randomUUID(), "Updated", null));
                assertEquals(httpStatus == 400 ? ErrorCode.CHANNEL_REQUEST_INVALID
                        : httpStatus == 404 ? ErrorCode.USER_NOT_FOUND : ErrorCode.USER_SERVICE_UNAVAILABLE, error.getErrorCode());
            }
            status = 200;
            for (String invalid : new String[]{"{}", "{\"status\":200,\"code\":1000,\"result\":{}}", "null"}) {
                reply = invalid;
                assertEquals(ErrorCode.USER_SERVICE_UNAVAILABLE,
                        assertThrows(AppException.class, () -> client.update(UUID.randomUUID(), "Updated", null)).getErrorCode());
            }
        } finally {
            status = 200;
            reply = validReply;
        }
    }

    @Test
    void transportFailurePropagatesWithoutRetrying() {
        UserProfileApiClient api = Mockito.mock(UserProfileApiClient.class);
        FeignException failure = Mockito.mock(FeignException.class);
        Mockito.when(failure.status()).thenReturn(-1);
        UUID userId = UUID.randomUUID();
        Mockito.when(api.update(userId, "Updated", null)).thenThrow(failure);
        AppException error = assertThrows(AppException.class,
                () -> new UserProfileClient(api).update(userId, "Updated", null));
        assertEquals(ErrorCode.USER_SERVICE_UNAVAILABLE, error.getErrorCode());
        Mockito.verify(api).update(userId, "Updated", null);
        Mockito.verifyNoMoreInteractions(api);
    }

    static HttpServer start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", exchange -> {
                method.set(exchange.getRequestMethod());
                path.set(exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            http.start();
            return http;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
