package com.reallive.channelservice.channel.controller.internal;

import com.reallive.channelservice.channel.client.streamer.ActiveStreamerResolver;
import com.reallive.channelservice.channel.dto.request.UpdateChannelProfileRequest;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.service.ChannelService;
import com.reallive.channelservice.common.service.R2StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "server.servlet.context-path=/channel",
        "spring.datasource.url=jdbc:h2:mem:channel_profile_update;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChannelProfileUpdateIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ChannelRepository channels;
    @Autowired
    private ChannelService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private R2StorageService storage;
    @MockitoBean
    private ActiveStreamerResolver streamerResolver;

    private ChannelEntity channel;

    @BeforeEach
    void setUp() {
        channel = createChannel();
    }

    @ParameterizedTest
    @EnumSource(ChannelStatus.class)
    void updatesNameAndAvatarForTheSuppliedStreamerWithoutUploadingOrCallingUserService(ChannelStatus channelStatus)
            throws Exception {
        channel.setStatus(channelStatus);
        channels.saveAndFlush(channel);
        ChannelEntity otherChannel = createChannel();
        Map<String, Object> originalFields = unchangedFields(channel.getId());
        String avatarUrl = "https://images.example.com/avatars/new.png";

        mvc.perform(patch(endpoint(channel.getStreamerId())).contextPath("/channel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "  New channel name  ", "avatarUrl", avatarUrl))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Channel profile updated successfully"))
                .andExpect(jsonPath("$.result").value(true))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        assertEquals(avatarUrl, channels.findById(channel.getId()).orElseThrow().getAvatarUrl());
        assertEquals("New channel name", channels.findById(channel.getId()).orElseThrow().getName());
        assertEquals(originalFields, unchangedFields(channel.getId()));
        assertEquals(otherChannel.getAvatarUrl(), channels.findById(otherChannel.getId()).orElseThrow().getAvatarUrl());
        assertEquals(otherChannel.getName(), channels.findById(otherChannel.getId()).orElseThrow().getName());
        verifyNoInteractions(storage, streamerResolver);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Renamed channel", "  Nguyễn Văn A  "})
    void updatesNameOnlyAndPreservesAvatar(String name) throws Exception {
        mvc.perform(patch(endpoint(channel.getStreamerId())).contextPath("/channel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value(true));

        ChannelEntity updated = channels.findById(channel.getId()).orElseThrow();
        assertEquals(name.strip(), updated.getName());
        assertEquals(channel.getAvatarUrl(), updated.getAvatarUrl());
        verifyNoInteractions(storage, streamerResolver);
    }

    @Test
    void nullAvatarPreservesAvatarWhenUpdatingName() throws Exception {
        mvc.perform(patch(endpoint(channel.getStreamerId())).contextPath("/channel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New name\",\"avatarUrl\":null}"))
                .andExpect(status().isOk());

        ChannelEntity updated = channels.findById(channel.getId()).orElseThrow();
        assertEquals("New name", updated.getName());
        assertEquals(channel.getAvatarUrl(), updated.getAvatarUrl());
    }

    @Test
    void nullNamePreservesNameWhenUpdatingAvatar() throws Exception {
        String avatarUrl = "https://images.example.com/new.png";
        mvc.perform(patch(endpoint(channel.getStreamerId())).contextPath("/channel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":null,\"avatarUrl\":\"" + avatarUrl + "\"}"))
                .andExpect(status().isOk());

        ChannelEntity updated = channels.findById(channel.getId()).orElseThrow();
        assertEquals(channel.getName(), updated.getName());
        assertEquals(avatarUrl, updated.getAvatarUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"avatarUrl\":null}", "{\"name\":null,\"avatarUrl\":null}"})
    void nullOrOmittedFieldsPreserveTheExistingChannel(String body) throws Exception {
        Map<String, Object> original = jdbc.queryForMap("SELECT * FROM channels WHERE id = ?", channel.getId());

        mvc.perform(patch(endpoint(channel.getStreamerId())).contextPath("/channel")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value(true));

        assertEquals(original, jdbc.queryForMap("SELECT * FROM channels WHERE id = ?", channel.getId()));
        verifyNoInteractions(storage, streamerResolver);
    }

    @Test
    void acceptsNameAtTheDatabaseLengthLimit() throws Exception {
        String name = "a".repeat(150);
        mvc.perform(patch(endpoint(channel.getStreamerId())).contextPath("/channel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isOk());

        assertEquals(name, channels.findById(channel.getId()).orElseThrow().getName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/avatar.png", "HTTPS://example.com/avatar.png"})
    void acceptsHttpAndHttpsUrlsAndRepeatedUpdates(String avatarUrl) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(patch(endpoint(channel.getStreamerId())).contextPath("/channel")
                            .contentType(MediaType.APPLICATION_JSON).content(body(avatarUrl)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.result").value(true));
        }
        assertEquals(avatarUrl, channels.findById(channel.getId()).orElseThrow().getAvatarUrl());
        assertEquals(channel.getName(), channels.findById(channel.getId()).orElseThrow().getName());
    }

    @Test
    void acceptsUrlAtTheDatabaseLengthLimit() throws Exception {
        String prefix = "https://example.com/";
        String avatarUrl = prefix + "a".repeat(500 - prefix.length());

        mvc.perform(patch(endpoint(channel.getStreamerId())).contextPath("/channel")
                        .contentType(MediaType.APPLICATION_JSON).content(body(avatarUrl)))
                .andExpect(status().isOk());

        assertEquals(avatarUrl, channels.findById(channel.getId()).orElseThrow().getAvatarUrl());
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejectsInvalidBodiesWithoutChangingNameOrAvatar(String body) throws Exception {
        mvc.perform(patch(endpoint(channel.getStreamerId())).contextPath("/channel")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1004));

        assertEquals(channel.getAvatarUrl(), channels.findById(channel.getId()).orElseThrow().getAvatarUrl());
        assertEquals(channel.getName(), channels.findById(channel.getId()).orElseThrow().getName());
        verifyNoInteractions(storage, streamerResolver);
    }

    static Stream<String> invalidBodies() {
        return Stream.of("", "null", "{", "[]",
                "{\"avatarUrl\":\"\"}", "{\"avatarUrl\":\"   \"}", "{\"avatarUrl\":\"/avatar.png\"}",
                "{\"avatarUrl\":\"ftp://example.com/avatar.png\"}", "{\"avatarUrl\":\"https://\"}",
                "{\"avatarUrl\":\"https://example.com/a b\"}",
                "{\"avatarUrl\":\"https://example.com:65536/avatar.png\"}",
                "{\"avatarUrl\":\"https://example.com/" + "a".repeat(482) + "\"}",
                "{\"name\":\"\"}", "{\"name\":\"   \"}", "{\"name\":\"\\t\\n\"}",
                "{\"name\":\"\u00a0\"}", "{\"name\":\"\u2003\"}",
                "{\"name\":\"" + "a".repeat(151) + "\"}",
                "{\"name\":\"New name\",\"avatarUrl\":\"invalid-url\"}",
                "{\"name\":\"   \",\"avatarUrl\":\"https://example.com/new.png\"}");
    }

    @Test
    void returnsNotFoundForMissingOrSoftDeletedChannel() throws Exception {
        channel.setDeletedAt(LocalDateTime.now());
        channels.saveAndFlush(channel);

        for (UUID streamerId : new UUID[]{UUID.randomUUID(), channel.getStreamerId()}) {
            mvc.perform(patch(endpoint(streamerId)).contextPath("/channel")
                            .contentType(MediaType.APPLICATION_JSON).content(body("https://example.com/new.png")))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1009));
        }
        assertEquals(channel.getAvatarUrl(), jdbc.queryForObject(
                "SELECT avatar_url FROM channels WHERE id = ?", String.class, channel.getId()));
    }

    @Test
    void invalidStreamerIdReturnsStandardBadRequest() throws Exception {
        mvc.perform(patch("/channel/internal/api/channels/by-streamer/invalid/avatar")
                        .contextPath("/channel").contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://example.com/new.png")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1004));
    }

    @Test
    void rejectsMultipartUploads() throws Exception {
        mvc.perform(multipart(endpoint(channel.getStreamerId())).contextPath("/channel")
                        .file(new MockMultipartFile("avatar", "avatar.png", "image/png", new byte[]{1}))
                        .with(request -> {
                            request.setMethod("PATCH");
                            return request;
                        }))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(1017));
        assertEquals(channel.getAvatarUrl(), channels.findById(channel.getId()).orElseThrow().getAvatarUrl());
    }

    @Test
    void unrelatedMethodsAndPathsStillRequireAuthentication() throws Exception {
        mvc.perform(get(endpoint(channel.getStreamerId())).contextPath("/channel"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(endpoint(channel.getStreamerId())).contextPath("/channel"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(endpoint(channel.getStreamerId()) + "/other").contextPath("/channel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void profileUpdateRollsBackWithCallerTransaction() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            service.updateChannelProfile(channel.getStreamerId(), UpdateChannelProfileRequest.builder()
                    .name("Changed name").avatarUrl("https://example.com/new.png").build());
            transaction.setRollbackOnly();
        });

        assertEquals(channel.getAvatarUrl(), channels.findById(channel.getId()).orElseThrow().getAvatarUrl());
        assertEquals(channel.getName(), channels.findById(channel.getId()).orElseThrow().getName());
    }

    private ChannelEntity createChannel() {
        UUID streamerId = UUID.randomUUID();
        return channels.saveAndFlush(ChannelEntity.builder()
                .streamerId(streamerId).name("Original channel").slug("channel-" + streamerId)
                .description("Original description").avatarUrl("https://example.com/original.png")
                .bannerUrl("https://example.com/banner.png").status(ChannelStatus.ACTIVE).build());
    }

    private String endpoint(UUID streamerId) {
        return "/channel/internal/api/channels/by-streamer/" + streamerId + "/avatar";
    }

    private String body(String avatarUrl) {
        return objectMapper.writeValueAsString(Map.of("avatarUrl", avatarUrl));
    }

    private Map<String, Object> unchangedFields(UUID channelId) {
        Map<String, Object> fields = new HashMap<>(jdbc.queryForMap("SELECT * FROM channels WHERE id = ?", channelId));
        fields.remove("NAME");
        fields.remove("AVATAR_URL");
        fields.remove("UPDATED_AT");
        return fields;
    }
}
