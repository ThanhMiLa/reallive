package com.reallive.userservice.client.channel;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import feign.FeignException;
import feign.RetryableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClientBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.cloud.openfeign.client.config.channel-service.connectTimeout=500",
        "spring.cloud.openfeign.client.config.channel-service.readTimeout=500"
})
@ActiveProfiles("test")
class ChannelClientIntegrationTest {

    private static final ChannelHttpTestServer CHANNEL = new ChannelHttpTestServer();
    @Autowired
    private ChannelClient client;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ChannelClientResolver resolver;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.service.channel", CHANNEL::baseUrl);
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:channel_feign;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    @BeforeEach
    void reset() {
        CHANNEL.reset();
    }

    @AfterEach
    void releaseDelayedReply() {
        CHANNEL.releaseResponse();
    }

    @AfterAll
    static void closeServer() {
        CHANNEL.close();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "https://images.example.com/avatar.png")
    void postsJsonToInternalUrlAndDecodesBooleanWithoutForwardingAuthentication(String avatarUrl) throws Exception {
        CreateChannelRequest payload = CreateChannelRequest.builder().streamerId(UUID.randomUUID())
                .name("Kênh \"của tôi\" \uD83C\uDFAE").avatarUrl(avatarUrl).build();

        var response = client.createChannel(payload);
        var request = CHANNEL.takeRequest();
        var json = mapper.readTree(request.body());

        assertEquals("POST", request.method());
        assertEquals("/channel/internal/api/channels", request.path());
        assertTrue(request.header("Content-Type").startsWith("application/json"));
        assertNull(request.header("Cookie"));
        assertNull(request.header("Authorization"));
        assertEquals(payload.getStreamerId().toString(), json.path("streamerId").asString());
        assertEquals(payload.getName(), json.path("name").asString());
        if (avatarUrl == null) {
            assertTrue(json.path("avatarUrl").isNull() || json.path("avatarUrl").isMissingNode());
        } else {
            assertEquals(avatarUrl, json.path("avatarUrl").asString());
        }
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1000, response.getBody().getCode());
        assertEquals(Boolean.TRUE, response.getBody().getResult());
        assertTrue(CHANNEL.hasChannel(payload.getStreamerId()));
        assertEquals(1, CHANNEL.callCount());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "New name,https://images.example.com/avatars/new.png",
            "New name,NULL",
            "NULL,https://images.example.com/avatars/new.png",
            "NULL,NULL"
    }, nullValues = "NULL")
    void patchesOptionalProfileFieldsToTheStreamersInternalEndpoint(String name, String avatarUrl) throws Exception {
        CreateChannelRequest channel = request();
        client.createChannel(channel);
        CHANNEL.takeRequest();
        var response = client.updateChannelProfile(channel.getStreamerId(),
                UpdateChannelProfileRequest.builder().name(name).avatarUrl(avatarUrl).build());
        var request = CHANNEL.takeRequest();

        assertEquals("PATCH", request.method());
        assertEquals("/channel/internal/api/channels/by-streamer/" + channel.getStreamerId() + "/avatar", request.path());
        assertTrue(request.header("Content-Type").startsWith("application/json"));
        assertNull(request.header("Cookie"));
        assertNull(request.header("Authorization"));
        var json = mapper.readTree(request.body());
        if (name == null) {
            assertTrue(json.path("name").isNull() || json.path("name").isMissingNode());
        } else {
            assertEquals(name, json.path("name").asString());
        }
        if (avatarUrl == null) {
            assertTrue(json.path("avatarUrl").isNull() || json.path("avatarUrl").isMissingNode());
        } else {
            assertEquals(avatarUrl, json.path("avatarUrl").asString());
        }
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(200, response.getBody().getStatus());
        assertEquals(1000, response.getBody().getCode());
        assertEquals(Boolean.TRUE, response.getBody().getResult());
        assertEquals(2, CHANNEL.callCount());

        assertTrue(resolver.updateChannelProfile(channel.getStreamerId(), name, avatarUrl));
        assertEquals(request.body(), CHANNEL.takeRequest().body());
        assertEquals(3, CHANNEL.callCount());
    }

    @ParameterizedTest
    @CsvSource({
            "400,CHANNEL_REQUEST_INVALID",
            "404,CHANNEL_NOT_FOUND",
            "401,CHANNEL_SERVICE_UNAVAILABLE",
            "403,CHANNEL_SERVICE_UNAVAILABLE",
            "415,CHANNEL_SERVICE_UNAVAILABLE",
            "500,CHANNEL_SERVICE_UNAVAILABLE",
            "503,CHANNEL_SERVICE_UNAVAILABLE"
    })
    void profileResolverMapsRealHttpFailuresWithoutRetry(int status, ErrorCode expectedError) {
        CHANNEL.setErrorStatus(status);

        AppException failure = assertThrows(AppException.class,
                () -> resolver.updateChannelProfile(UUID.randomUUID(), "New name", "https://images.example.com/avatar.png"));

        assertEquals(expectedError, failure.getErrorCode());
        assertEquals(1, CHANNEL.callCount());
    }

    @ParameterizedTest
    @EnumSource(value = ChannelHttpTestServer.Mode.class,
            names = {"INVALID_JSON", "EMPTY_BODY", "FALSE_RESULT", "NULL_RESULT"})
    void profileResolverRejectsMalformedOrUnsuccessfulHttpResponses(ChannelHttpTestServer.Mode mode) {
        CreateChannelRequest channel = request();
        client.createChannel(channel);
        CHANNEL.setMode(mode);

        AppException failure = assertThrows(AppException.class,
                () -> resolver.updateChannelProfile(channel.getStreamerId(), "New name", "https://images.example.com/avatar.png"));

        assertEquals(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE, failure.getErrorCode());
        assertEquals(2, CHANNEL.callCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 500, 503})
    void preservesHttpFailureStatusWithoutRetry(int status) {
        CHANNEL.setErrorStatus(status);

        FeignException failure = assertThrows(FeignException.class, () -> client.createChannel(request()));

        assertEquals(status, failure.status());
        assertEquals(1, CHANNEL.callCount());
    }

    @Test
    void malformedResponseFailsDecodingWithoutRetry() {
        CHANNEL.setMode(ChannelHttpTestServer.Mode.INVALID_JSON);

        assertThrows(FeignException.class, () -> client.createChannel(request()));
        assertEquals(1, CHANNEL.callCount());
    }

    @Test
    void emptyHttpSuccessHasNoBody() {
        CHANNEL.setMode(ChannelHttpTestServer.Mode.EMPTY_BODY);

        assertNull(client.createChannel(request()).getBody());
        assertEquals(1, CHANNEL.callCount());
    }

    @Test
    void lostResponseTimesOutAfterRemoteSaveWithoutRetry() {
        CreateChannelRequest payload = request();
        CHANNEL.setMode(ChannelHttpTestServer.Mode.LOST_RESPONSE);

        assertThrows(RetryableException.class, () -> client.createChannel(payload));
        assertTrue(CHANNEL.hasChannel(payload.getStreamerId()));
        assertEquals(1, CHANNEL.callCount());
    }

    @Test
    void unavailablePortFailsWithoutReachingChannel() throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = unused.getLocalPort();
        }
        ChannelClient unavailable = new FeignClientBuilder(context).forType(ChannelClient.class, "channel-unavailable")
                .url("http://127.0.0.1:" + port + "/channel").build();

        assertThrows(RetryableException.class, () -> unavailable.createChannel(request()));
        assertEquals(0, CHANNEL.callCount());
    }

    private CreateChannelRequest request() {
        return CreateChannelRequest.builder().streamerId(UUID.randomUUID()).name("Channel").build();
    }
}
