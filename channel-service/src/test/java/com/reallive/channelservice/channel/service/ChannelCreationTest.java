package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.dto.request.CreateChannelRequest;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChannelCreationTest {

    private static final String SCHEMA = "channel_creation_" + UUID.randomUUID().toString().replace("-", "");
    private static final String ENDPOINT = "/channel/internal/api/channels";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChannelService channelService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private ChannelRepository channels;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("server.servlet.context-path", () -> "/channel");
        registry.add("spring.datasource.url", () -> System.getProperty("channel.test.database-url",
                "jdbc:h2:mem:channel_creation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username", () -> System.getProperty("channel.test.database-username", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("channel.test.database-password", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "\"" + SCHEMA + "\"");
    }

    @AfterAll
    static void dropTestSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void createsChannelWithoutJwtOrInternalTokenAndPersistsDefaults() throws Exception {
        CreateChannelRequest request = request("  Kênh của tôi  ", "https://example.com/avatar.png");

        mvc.perform(post(ENDPOINT).contextPath("/channel")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Channel created successfully"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.result").value(true));

        ChannelEntity persisted = channels.findByStreamerId(request.getStreamerId()).orElseThrow();
        assertEquals("Kênh của tôi", persisted.getName());
        assertNotNull(persisted.getId());
        assertEquals("channel-" + request.getStreamerId(), persisted.getSlug());
        assertEquals(request.getAvatarUrl(), persisted.getAvatarUrl());
        assertNotNull(persisted.getCreatedAt());
        assertNotNull(persisted.getUpdatedAt());
        assertNull(persisted.getDescription());
        assertNull(persisted.getBannerUrl());
        assertNull(persisted.getDeletedAt());
        assertEquals(ChannelStatus.ACTIVE, persisted.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/avatar.png", "https://example.com/avatar.png", "HTTPS://example.com/avatar.png"})
    void acceptsHttpAndHttpsAvatarUrls(String avatarUrl) throws Exception {
        CreateChannelRequest request = request("Channel", avatarUrl);
        mvc.perform(post(ENDPOINT).contextPath("/channel").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(true));
        assertEquals(avatarUrl, channels.findByStreamerId(request.getStreamerId()).orElseThrow().getAvatarUrl());
    }

    @Test
    void acceptsMissingAvatarAndMaximumNameLengthAfterTrimming() throws Exception {
        UUID streamerId = UUID.randomUUID();
        Map<String, Object> requestBody = Map.of("streamerId", streamerId, "name", "  " + "a".repeat(150) + "  ");
        mvc.perform(post(ENDPOINT).contextPath("/channel").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(true));
        ChannelEntity persisted = channels.findByStreamerId(streamerId).orElseThrow();
        assertEquals("a".repeat(150), persisted.getName());
        assertEquals("", persisted.getAvatarUrl());
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidRequestsWithoutCreatingChannels(String body) throws Exception {
        long countBefore = channels.count();
        mvc.perform(post(ENDPOINT).contextPath("/channel").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(1004))
                .andExpect(jsonPath("$.message").value("Invalid channel request"))
                .andExpect(jsonPath("$.result").value(false))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
        assertEquals(countBefore, channels.count());
    }

    static Stream<String> invalidRequests() {
        String validPrefix = "{\"streamerId\":\"" + UUID.randomUUID() + "\",\"name\":\"Channel\"";
        return Stream.concat(Stream.of("", "null", "{", "[]", "{}", "{\"name\":\"Channel\"}",
                        "{\"streamerId\":\"invalid-uuid\",\"name\":\"Channel\"}",
                        "{\"streamerId\":null,\"name\":\"Channel\"}",
                        "{\"streamerId\":\"" + UUID.randomUUID() + "\"}",
                        "{\"streamerId\":\"" + UUID.randomUUID() + "\",\"name\":null}",
                        "{\"streamerId\":\"" + UUID.randomUUID() + "\",\"name\":\"   \"}",
                        "{\"streamerId\":\"" + UUID.randomUUID() + "\",\"name\":\"" + "a".repeat(151) + "\"}"),
                Stream.of("", " ", "ftp://example.com/image", "javascript:alert(1)", "/avatar.png",
                                "https://", "http:///avatar.png", "https://example.com/a b", "https://example.com:65536/avatar",
                                "https://example.com:invalid/avatar", "https://example.com/" + "a".repeat(501))
                        .map(avatar -> validPrefix + ",\"avatarUrl\":\"" + avatar + "\"}"));
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,false", "ACTIVE,true", "BANNED,false", "BANNED,true"})
    void duplicateStreamerReturns400AndPreservesExistingChannel(ChannelStatus status, boolean deleted) throws Exception {
        CreateChannelRequest request = request("Original name", "https://example.com/original.png");
        channelService.createChannel(request);
        ChannelEntity original = channels.findByStreamerId(request.getStreamerId()).orElseThrow();
        original.setStatus(status);
        original.setDescription("Original description");
        original.setBannerUrl("https://example.com/banner.png");
        original.setDeletedAt(deleted ? LocalDateTime.of(2026, 1, 1, 12, 0) : null);
        channels.saveAndFlush(original);
        String storedChannelQuery = "SELECT * FROM \"" + SCHEMA + "\".channels WHERE id = ?";
        Map<String, Object> originalRow = jdbc.queryForMap(storedChannelQuery, original.getId());
        request.setName("Replacement name");
        request.setAvatarUrl("https://example.com/replacement.png");
        clearInvocations(channels);

        mvc.perform(post(ENDPOINT).contextPath("/channel").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(1005))
                .andExpect(jsonPath("$.message").value("Channel already existed"))
                .andExpect(jsonPath("$.result").value(false));

        assertEquals(originalRow, jdbc.queryForMap(storedChannelQuery, original.getId()));
        assertEquals(deleted, channels.findByStreamerId(request.getStreamerId()).isEmpty());
        AppException exception = assertThrows(AppException.class, () -> channelService.createChannel(request));
        assertEquals(ErrorCode.CHANNEL_ALREADY_EXISTED, exception.getErrorCode());
        verify(channels, never()).saveAndFlush(any(ChannelEntity.class));
    }

    @Test
    void concurrentCreatesAllowOneSuccessAndRejectTheDuplicate() throws Exception {
        CreateChannelRequest firstRequest = request("First name", "https://example.com/first.png");
        CreateChannelRequest secondRequest = CreateChannelRequest.builder()
                .streamerId(firstRequest.getStreamerId()).name("Second name").build();
        CountDownLatch insertsReady = new CountDownLatch(2);
        var repositoryAnswer = mockingDetails(channels).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            insertsReady.countDown();
            assertTrue(insertsReady.await(10, TimeUnit.SECONDS), "Both requests must attempt insertion");
            return repositoryAnswer.answer(invocation);
        }).when(channels).saveAndFlush(any(ChannelEntity.class));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> channelService.createChannel(firstRequest));
            var second = executor.submit(() -> channelService.createChannel(secondRequest));
            int successes = 0;
            int failures = 0;
            for (var attempt : List.of(first, second)) {
                try {
                    assertTrue(attempt.get(20, TimeUnit.SECONDS));
                    successes++;
                } catch (ExecutionException exception) {
                    AppException failure = assertInstanceOf(AppException.class, exception.getCause());
                    assertEquals(ErrorCode.CHANNEL_DATA_UNAVAILABLE, failure.getErrorCode());
                    failures++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, failures);
        }
        verify(channels, times(2)).saveAndFlush(any(ChannelEntity.class));
        assertEquals("channel-" + firstRequest.getStreamerId(),
                channels.findByStreamerId(firstRequest.getStreamerId()).orElseThrow().getSlug());
        assertEquals(1, channels.findAll().stream()
                .filter(channel -> channel.getStreamerId().equals(firstRequest.getStreamerId())).count());
    }

    @Test
    void channelCreationRollsBackWithCallerTransaction() {
        CreateChannelRequest request = request("Channel in caller transaction", null);
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        Boolean success = outerTransaction.execute(status -> {
            boolean created = channelService.createChannel(request);
            assertTrue(channels.existsByStreamerId(request.getStreamerId()));
            status.setRollbackOnly();
            return created;
        });

        assertEquals(Boolean.TRUE, success);
        assertFalse(channels.existsByStreamerId(request.getStreamerId()));
    }

    @Test
    void persistenceFailureRollsBackInsertAndReturnsSanitized503() throws Exception {
        CreateChannelRequest request = request("Failed channel", null);
        var repositoryAnswer = mockingDetails(channels).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            repositoryAnswer.answer(invocation);
            throw new DataAccessResourceFailureException("private database connection details");
        }).when(channels).saveAndFlush(any(ChannelEntity.class));

        mvc.perform(post(ENDPOINT).contextPath("/channel").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value(1003))
                .andExpect(jsonPath("$.message").value("Channel data unavailable"))
                .andExpect(jsonPath("$.result").value(false));

        assertFalse(channels.existsByStreamerId(request.getStreamerId()));
    }

    @Test
    void unrelatedSlugConflictIsNotReportedAsSuccessfulCreation() {
        CreateChannelRequest request = request("Conflicting channel", null);
        channels.saveAndFlush(ChannelEntity.builder().streamerId(UUID.randomUUID()).name("Other streamer")
                .slug("channel-" + request.getStreamerId()).status(ChannelStatus.ACTIVE).build());

        AppException exception = assertThrows(AppException.class, () -> channelService.createChannel(request));
        assertEquals(ErrorCode.CHANNEL_DATA_UNAVAILABLE, exception.getErrorCode());
        assertFalse(channels.existsByStreamerId(request.getStreamerId()));
    }

    @Test
    void nonIntegrityPersistenceFailurePropagatesFromService() {
        CreateChannelRequest request = request("Channel", null);
        doThrow(new DataAccessResourceFailureException("private connection details"))
                .when(channels).saveAndFlush(any(ChannelEntity.class));

        assertThrows(DataAccessResourceFailureException.class, () -> channelService.createChannel(request));
        assertFalse(channels.existsByStreamerId(request.getStreamerId()));
    }

    @Test
    void repositoryReturningNoIdProducesFalseInsteadOfReportingSuccess() throws Exception {
        CreateChannelRequest request = request("Channel without generated ID", null);
        doAnswer(invocation -> invocation.getArgument(0)).when(channels).saveAndFlush(any(ChannelEntity.class));

        mvc.perform(post(ENDPOINT).contextPath("/channel").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value(false));

        assertFalse(channels.existsByStreamerId(request.getStreamerId()));
    }

    @Test
    void lookupFailureReturns503WithoutAttemptingInsert() throws Exception {
        CreateChannelRequest request = request("Channel with unavailable storage", null);
        doThrow(new DataAccessResourceFailureException("private connection details"))
                .when(channels).existsByStreamerIdIncludingDeleted(request.getStreamerId());
        clearInvocations(channels);

        mvc.perform(post(ENDPOINT).contextPath("/channel").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1003))
                .andExpect(jsonPath("$.message").value("Channel data unavailable"))
                .andExpect(jsonPath("$.result").value(false));

        verify(channels, never()).saveAndFlush(any(ChannelEntity.class));
    }

    @Test
    void directServiceCallPersistsNormalizedBuilderName() {
        CreateChannelRequest validRequest = request("  " + "a".repeat(150) + "  ", null);
        assertTrue(channelService.createChannel(validRequest));
        assertEquals("a".repeat(150), channels.findByStreamerId(validRequest.getStreamerId()).orElseThrow().getName());
    }

    @Test
    void getAtInternalPathStillRequiresAuthentication() throws Exception {
        mvc.perform(get(ENDPOINT).contextPath("/channel"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/channel/internal/api/channels/other", "/channel/api/channels"})
    void otherPostPathsStillRequireAuthentication(String path) throws Exception {
        mvc.perform(post(path).contextPath("/channel").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
    }

    private static CreateChannelRequest request(String name, String avatarUrl) {
        return CreateChannelRequest.builder().streamerId(UUID.randomUUID()).name(name).avatarUrl(avatarUrl).build();
    }
}
