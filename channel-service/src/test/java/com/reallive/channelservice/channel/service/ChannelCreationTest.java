package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.client.user.UserChatProfileClient;
import com.reallive.channelservice.channel.dto.response.OwnChannelResponse;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.client.streamer.ActiveStreamerResponse;
import com.reallive.channelservice.client.streamer.StreamerClient;
import com.reallive.channelservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.channelservice.common.dto.ApiResponse;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import com.reallive.channelservice.common.security.CustomJwtDecoder;
import feign.FeignException;
import feign.Request;
import feign.Response;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChannelCreationTest {
    private static final String SCHEMA = "channel_creation_" + UUID.randomUUID().toString().replace("-", "");
    private static final String ENDPOINT = "/channel/api/channels/me";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ChannelService channelService;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private ChannelRepository channels;
    @MockitoBean private StreamerClient streamers;
    @MockitoBean private UserChatProfileClient profiles;
    @MockitoBean private CustomJwtDecoder decoder;
    @MockitoBean private RedisAuthoritiesReader authorities;
    private UUID userId;
    private UUID streamerId;

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

    @BeforeEach
    void prepare() {
        userId = UUID.randomUUID();
        streamerId = UUID.randomUUID();
        when(decoder.decode("channel-token")).thenReturn(Jwt.withTokenValue("channel-token")
                .header("alg", "HS512").subject(userId.toString()).build());
        when(authorities.read(userId)).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_STREAMER")));
        when(streamers.getActiveStreamerIdByUserId(userId)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Streamer HTTP call must not hold a transaction");
            return ResponseEntity.ok(ApiResponse.<ActiveStreamerResponse>builder()
                    .status(200).code(1000).result(new ActiveStreamerResponse(streamerId)).build());
        });
        when(profiles.profile(userId)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Profile HTTP call must not hold a transaction");
            return profile("Kênh của tôi", "https://example.com/avatar.png");
        });
    }

    @Test
    void createsFromAuthenticatedIdentityAndRepeatReturnsSameChannelWithoutRemoteCalls() throws Exception {
        mvc.perform(post(ENDPOINT).contextPath("/channel").cookie(cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000)).andExpect(jsonPath("$.message").value("Channel ready"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.result.streamerId").value(streamerId.toString()))
                .andExpect(jsonPath("$.result.name").value("Kênh của tôi"))
                .andExpect(jsonPath("$.result.avatarUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.result.categories").isEmpty())
                .andExpect(jsonPath("$.result.numberOfFollower").value(0));
        ChannelEntity original = channels.findByStreamerId(streamerId).orElseThrow();
        assertEquals(userId, original.getUserId());
        assertEquals("channel-" + streamerId, original.getSlug());
        assertEquals(ChannelStatus.ACTIVE, original.getStatus());
        assertNotNull(original.getCreatedAt());
        assertNotNull(original.getUpdatedAt());
        assertNull(original.getDescription());
        assertNull(original.getBannerUrl());
        assertNull(original.getDeletedAt());
        clearInvocations(streamers, profiles);
        mvc.perform(post(ENDPOINT).contextPath("/channel").cookie(cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.id").value(original.getId().toString()));
        assertEquals(original.getUpdatedAt(), channels.findById(original.getId()).orElseThrow().getUpdatedAt());
        verifyNoInteractions(streamers, profiles);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void missingOrBlankDisplayNameAndNullAvatarUseDefaults(String displayName) {
        when(profiles.profile(userId)).thenReturn(profile(displayName, null));
        OwnChannelResponse result = channelService.createOwnChannel(userId);
        assertEquals("Streamer", result.getName());
        assertEquals("", result.getAvatarUrl());
    }

    @ParameterizedTest
    @EnumSource(ChannelStatus.class)
    void ownerFastPathIncludesCategoriesAndPreservesProfile(ChannelStatus channelStatus) {
        ChannelEntity existing = savedChannel(userId);
        existing.setStatus(channelStatus);
        channels.saveAndFlush(existing);
        UUID categoryId = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + SCHEMA + "\".categories (id, name, slug, status) VALUES (?, ?, ?, 'ACTIVE')",
                categoryId, "Category " + categoryId, "category-" + categoryId);
        jdbc.update("INSERT INTO \"" + SCHEMA + "\".channel_categories (channel_id, category_id) VALUES (?, ?)",
                existing.getId(), categoryId);
        clearInvocations(streamers, profiles);
        OwnChannelResponse result = channelService.createOwnChannel(userId);
        assertEquals(existing.getId(), result.getId());
        assertEquals("Original", result.getName());
        assertEquals(1, result.getCategories().size());
        assertEquals(channelStatus.name(), result.getStatus());
        verifyNoInteractions(streamers, profiles);
    }

    @Test
    void legacyStreamerFastPathDoesNotFetchProfile() {
        ChannelEntity existing = savedChannel(null);
        OwnChannelResponse result = channelService.createOwnChannel(userId);
        assertEquals(existing.getId(), result.getId());
        assertEquals("Original", result.getName());
        verify(streamers).getActiveStreamerIdByUserId(userId);
        verifyNoInteractions(profiles);
    }

    @Test
    void softDeletedChannelCannotBeRecreated() throws Exception {
        ChannelEntity deleted = savedChannel(userId);
        deleted.setDeletedAt(LocalDateTime.now());
        channels.saveAndFlush(deleted);
        mvc.perform(post(ENDPOINT).contextPath("/channel").cookie(cookie()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1010));
        verifyNoInteractions(profiles);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM \"" + SCHEMA + "\".channels WHERE streamer_id = ?",
                Integer.class, streamerId));
    }

    @ParameterizedTest
    @CsvSource({"403,403,1007", "404,404,1006", "500,503,1008", "503,503,1008"})
    void upstreamStreamerFailuresDoNotCreateChannel(int upstreamStatus, int expectedStatus, int code) throws Exception {
        when(streamers.getActiveStreamerIdByUserId(userId)).thenThrow(remoteFailure(upstreamStatus));
        mvc.perform(post(ENDPOINT).contextPath("/channel").cookie(cookie()))
                .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(code));
        assertTrue(channels.findByUserId(userId).isEmpty());
        verifyNoInteractions(profiles);
    }

    @Test
    void profileServiceFailureDoesNotCreateChannel() throws Exception {
        when(profiles.profile(userId)).thenThrow(remoteFailure(503));
        mvc.perform(post(ENDPOINT).contextPath("/channel").cookie(cookie()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1008));
        assertTrue(channels.findByUserId(userId).isEmpty());
    }

    @Test
    void concurrentCreatesBothReturnSamePersistedChannel() throws Exception {
        CountDownLatch bothProfiles = new CountDownLatch(2);
        when(profiles.profile(userId)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            bothProfiles.countDown();
            assertTrue(bothProfiles.await(10, TimeUnit.SECONDS));
            return profile("Concurrent", null);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> channelService.createOwnChannel(userId));
            var second = executor.submit(() -> channelService.createOwnChannel(userId));
            assertEquals(first.get(20, TimeUnit.SECONDS).getId(), second.get(20, TimeUnit.SECONDS).getId());
        }
        verify(channels, times(2)).insertIfAbsent(streamerId, userId, "Concurrent", "channel-" + streamerId, "", "ACTIVE");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM \"" + SCHEMA + "\".channels WHERE streamer_id = ?",
                Integer.class, streamerId));
    }

    @Test
    void unrelatedSlugConflictDoesNotReportSuccess() {
        channels.saveAndFlush(ChannelEntity.builder().streamerId(UUID.randomUUID()).name("Other")
                .slug("channel-" + streamerId).status(ChannelStatus.ACTIVE).build());
        AppException failure = assertThrows(AppException.class, () -> channelService.createOwnChannel(userId));
        assertEquals(ErrorCode.CHANNEL_DATA_UNAVAILABLE, failure.getErrorCode());
        assertTrue(channels.findByStreamerId(streamerId).isEmpty());
    }

    @Test
    void failureAfterInsertRollsBackLocalTransaction() throws Exception {
        var repositoryAnswer = mockingDetails(channels).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            repositoryAnswer.answer(invocation);
            throw new DataAccessResourceFailureException("private database details");
        }).when(channels).insertIfAbsent(eq(streamerId), eq(userId), anyString(), anyString(), anyString(), eq("ACTIVE"));
        mvc.perform(post(ENDPOINT).contextPath("/channel").cookie(cookie()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1003));
        assertTrue(channels.findByStreamerId(streamerId).isEmpty());
    }

    @Test
    void anonymousAndNonStreamerRequestsCannotCreateChannels() throws Exception {
        mvc.perform(post(ENDPOINT).contextPath("/channel")).andExpect(status().isUnauthorized());
        when(authorities.read(userId)).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_MODERATOR"),
                new SimpleGrantedAuthority("CHANNEL_UPDATE")));
        mvc.perform(post(ENDPOINT).contextPath("/channel").cookie(cookie())).andExpect(status().isForbidden());
        verifyNoInteractions(streamers, profiles);
        assertTrue(channels.findByUserId(userId).isEmpty());
    }

    @Test
    void removedInternalPathRequiresAuthenticationAndHasNoHandler() throws Exception {
        mvc.perform(post("/channel/internal/api/channels").contextPath("/channel"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/channel/internal/api/channels").contextPath("/channel").cookie(cookie()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(streamers, profiles);
    }

    @Test
    void callerTransactionIsSuspendedDuringRemoteCalls() {
        OwnChannelResponse result = new TransactionTemplate(transactionManager).execute(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            OwnChannelResponse channel = channelService.createOwnChannel(userId);
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            status.setRollbackOnly();
            return channel;
        });
        assertNotNull(result);
        assertEquals(result.getId(), channels.findByStreamerId(streamerId).orElseThrow().getId());
    }

    @Test
    void missingUserIdIsUnauthenticated() {
        assertEquals(ErrorCode.UNAUTHENTICATED,
                assertThrows(AppException.class, () -> channelService.createOwnChannel(null)).getErrorCode());
        verifyNoInteractions(streamers, profiles);
    }

    private ChannelEntity savedChannel(UUID ownerId) {
        return channels.saveAndFlush(ChannelEntity.builder().streamerId(streamerId).userId(ownerId)
                .name("Original").slug("channel-" + streamerId).avatarUrl("original.png").status(ChannelStatus.ACTIVE).build());
    }

    private ApiResponse<UserChatProfileClient.ChatProfile> profile(String displayName, String avatarUrl) {
        return ApiResponse.<UserChatProfileClient.ChatProfile>builder().status(200).code(1000)
                .result(new UserChatProfileClient.ChatProfile(userId, displayName, avatarUrl, "username")).build();
    }

    private Cookie cookie() {
        return new Cookie("access_token", "channel-token");
    }

    private FeignException remoteFailure(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "http://user/internal", Map.of(), null, StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("user", Response.builder().request(request).status(status).headers(Map.of()).build());
    }
}
