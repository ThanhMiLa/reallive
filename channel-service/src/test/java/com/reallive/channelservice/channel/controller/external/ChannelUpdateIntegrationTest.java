package com.reallive.channelservice.channel.controller.external;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.channelservice.channel.client.streamer.StreamerHttpTestServer;
import com.reallive.channelservice.channel.dto.request.UpdateChannelRequest;
import com.reallive.channelservice.channel.entity.CategoryEntity;
import com.reallive.channelservice.channel.entity.ChannelCategoryEntity;
import com.reallive.channelservice.channel.entity.ChannelCategoryId;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.enums.CategoryStatus;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.CategoryRepository;
import com.reallive.channelservice.channel.repository.ChannelCategoryRepository;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.service.ChannelService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "server.servlet.context-path=/channel",
        "spring.datasource.url=jdbc:h2:mem:channel_update;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.cloud.openfeign.client.config.user-service.connectTimeout=300",
        "spring.cloud.openfeign.client.config.user-service.readTimeout=300"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChannelUpdateIntegrationTest {

    private static final String ENDPOINT = "/channel/api/channels/me";
    private static final StreamerHttpTestServer USER_SERVICE = new StreamerHttpTestServer();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private ChannelService service;
    @MockitoSpyBean
    private ChannelRepository channels;
    @Autowired
    private ChannelCategoryRepository associations;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID userId;
    private ChannelEntity channel;
    private CategoryEntity retainedCategory;
    private CategoryEntity removedCategory;
    private CategoryEntity addedCategory;

    @DynamicPropertySource
    static void clientProperties(DynamicPropertyRegistry registry) {
        registry.add("app.service.user", USER_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopUserService() {
        USER_SERVICE.close();
    }

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        channel = createChannel(UUID.randomUUID());
        USER_SERVICE.reset(channel.getStreamerId());
        retainedCategory = createCategory(CategoryStatus.ACTIVE);
        removedCategory = createCategory(CategoryStatus.ACTIVE);
        addedCategory = createCategory(CategoryStatus.ACTIVE);
        associations.saveAllAndFlush(List.of(link(retainedCategory), link(removedCategory)));
    }

    @Test
    void updatesOnlyTheAuthenticatedUsersChannelAndReplacesDistinctCategories() throws Exception {
        ChannelEntity otherChannel = createChannel(UUID.randomUUID());
        LocalDateTime retainedCreatedAt = associations.findById(
                new ChannelCategoryId(channel.getId(), retainedCategory.getId())).orElseThrow().getCreatedAt();
        var payload = Map.of("description", "Updated description", "bannerUrl", "https://example.com/new.png",
                "categoryIds", List.of(retainedCategory.getId(), addedCategory.getId(), addedCategory.getId()),
                "streamerId", otherChannel.getStreamerId(), "channelId", otherChannel.getId());

        mvc.perform(patch(ENDPOINT).contextPath("/channel").cookie(cookie("CHANNEL_UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.id").value(channel.getId().toString()))
                .andExpect(jsonPath("$.result.streamerId").value(channel.getStreamerId().toString()))
                .andExpect(jsonPath("$.result.name").value("Original channel"))
                .andExpect(jsonPath("$.result.slug").value(channel.getSlug()))
                .andExpect(jsonPath("$.result.avatarUrl").value(channel.getAvatarUrl()))
                .andExpect(jsonPath("$.result.description").value("Updated description"))
                .andExpect(jsonPath("$.result.bannerUrl").value("https://example.com/new.png"))
                .andExpect(jsonPath("$.result.status").value("ACTIVE"))
                .andExpect(jsonPath("$.result.categoryIds", containsInAnyOrder(
                        retainedCategory.getId().toString(), addedCategory.getId().toString())));

        assertEquals(List.of("GET /user/internal/api/streamers/by-user/" + userId + "/active"),
                List.copyOf(USER_SERVICE.requests()));
        assertEquals(Set.of(retainedCategory.getId(), addedCategory.getId()), assignedIds());
        assertEquals(retainedCreatedAt, associations.findById(
                new ChannelCategoryId(channel.getId(), retainedCategory.getId())).orElseThrow().getCreatedAt());
        ChannelEntity saved = channels.findById(channel.getId()).orElseThrow();
        assertEquals("Updated description", saved.getDescription());
        assertEquals("https://example.com/new.png", saved.getBannerUrl());
        assertEquals("Original description", channels.findById(otherChannel.getId()).orElseThrow().getDescription());
    }

    @Test
    void emptyStringsClearDescriptionAndBanner() throws Exception {
        mvc.perform(patch(ENDPOINT).contextPath("/channel").cookie(cookie("CHANNEL_UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(request("", "", retainedCategory.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.description").value(nullValue()))
                .andExpect(jsonPath("$.result.bannerUrl").value(nullValue()));

        ChannelEntity saved = channels.findById(channel.getId()).orElseThrow();
        assertNull(saved.getDescription());
        assertNull(saved.getBannerUrl());
    }

    @Test
    void requiresAuthenticationBeforeCallingUserService() throws Exception {
        mvc.perform(patch(ENDPOINT).contextPath("/channel").contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRequest())))
                .andExpect(status().isUnauthorized());
        assertTrue(USER_SERVICE.requests().isEmpty());
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ROLE_STREAMER", "ROLE_ADMIN", "CHANNEL_READ"})
    void roleOrOtherAuthorityCannotReplaceTheUpdatePermission(String scope) throws Exception {
        mvc.perform(patch(ENDPOINT).contextPath("/channel").cookie(cookie(scope))
                        .contentType(MediaType.APPLICATION_JSON).content(json(validRequest())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        assertTrue(USER_SERVICE.requests().isEmpty());
        assertUnchanged();
    }

    @Test
    void malformedAuthenticatedUserIdCannotReachUserService() throws Exception {
        mvc.perform(patch(ENDPOINT).contextPath("/channel").cookie(cookie("CHANNEL_UPDATE", "invalid-user-id"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(validRequest())))
                .andExpect(status().isUnauthorized());
        assertTrue(USER_SERVICE.requests().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidDtoNeverCallsUserServiceOrChangesDatabase(String body) throws Exception {
        mvc.perform(patch(ENDPOINT).contextPath("/channel").cookie(cookie("CHANNEL_UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1004));
        assertTrue(USER_SERVICE.requests().isEmpty());
        assertUnchanged();
    }

    @ParameterizedTest
    @CsvSource({"404,404,1006", "403,403,1007", "401,503,1008", "500,503,1008", "503,503,1008"})
    void upstreamRejectionStopsTheUpdate(int upstreamStatus, int expectedStatus, int expectedCode) throws Exception {
        USER_SERVICE.reply(upstreamStatus, "{\"status\":" + upstreamStatus + ",\"result\":null}");
        mvc.perform(patch(ENDPOINT).contextPath("/channel").cookie(cookie("CHANNEL_UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(validRequest())))
                .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(expectedCode));
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{}", "{\"status\":200,\"code\":1000,\"result\":null}",
            "{\"status\":200,\"code\":1000,\"result\":{}}",
            "{\"status\":200,\"code\":1000,\"result\":{\"streamerId\":\"invalid-uuid\"}}"})
    void invalidUpstreamSuccessBodyStopsTheUpdate(String body) throws Exception {
        USER_SERVICE.reply(200, body);
        mvc.perform(patch(ENDPOINT).contextPath("/channel").cookie(cookie("CHANNEL_UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(validRequest())))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1008));
        assertUnchanged();
    }

    @Test
    void upstreamTimeoutStopsTheUpdate() throws Exception {
        USER_SERVICE.delay(1000);
        mvc.perform(patch(ENDPOINT).contextPath("/channel").cookie(cookie("CHANNEL_UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(validRequest())))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1008));
        assertUnchanged();
    }

    @Test
    void missingChannelIsNotCreated() throws Exception {
        USER_SERVICE.reset(UUID.randomUUID());
        long originalCount = channels.count();
        performFailure(validRequest(), 404, 1009);
        assertEquals(originalCount, channels.count());
    }

    @Test
    void deletedChannelCannotBeUpdated() throws Exception {
        channel.setDeletedAt(LocalDateTime.now());
        channels.saveAndFlush(channel);
        performFailure(validRequest(), 404, 1009);
        assertTrue(channels.findById(channel.getId()).isEmpty());
    }

    @Test
    void bannedChannelCannotBeUpdated() throws Exception {
        channel.setStatus(ChannelStatus.BANNED);
        channels.saveAndFlush(channel);
        performFailure(validRequest(), 403, 1010);
    }

    @Test
    void missingCategoryRejectsTheEntireUpdate() throws Exception {
        performFailure(request("Updated", "https://example.com/new.png", retainedCategory.getId(), UUID.randomUUID()), 400, 1011);
    }

    @Test
    void inactiveCategoryRejectsTheEntireUpdate() throws Exception {
        CategoryEntity inactive = createCategory(CategoryStatus.INACTIVE);
        performFailure(request("Updated", "https://example.com/new.png", retainedCategory.getId(), inactive.getId()), 400, 1012);
    }

    @Test
    void databaseConstraintFailureRollsBackProfileAndCategoryReplacement() throws Exception {
        jdbc.execute("ALTER TABLE channels ADD CONSTRAINT ck_channel_update_test "
                + "CHECK (description IS NULL OR description <> 'reject-at-commit')");
        try {
            performFailure(request("reject-at-commit", "https://example.com/new.png",
                    retainedCategory.getId(), addedCategory.getId()), 503, 1003);
        } finally {
            jdbc.execute("ALTER TABLE channels DROP CONSTRAINT ck_channel_update_test");
        }
    }

    @Test
    void concurrentUpdatesWaitForTheSameChannelAndNeverMergeCategorySelections() throws Exception {
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempt = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        var repositoryAnswer = mockingDetails(channels).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 2) {
                secondAttempt.countDown();
            }
            Object lockedChannel = repositoryAnswer.answer(invocation);
            if (attempt == 1) {
                firstLocked.countDown();
                if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out releasing the first channel update");
                }
            }
            return lockedChannel;
        }).when(channels).findByStreamerIdForUpdate(channel.getStreamerId());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.updateChannel(userId,
                    request("First", "", retainedCategory.getId())));
            try {
                assertTrue(firstLocked.await(5, TimeUnit.SECONDS));
                var second = executor.submit(() -> service.updateChannel(userId,
                        request("Second", "", addedCategory.getId())));
                assertTrue(secondAttempt.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
                releaseFirst.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
            }
        }
        assertEquals("Second", channels.findById(channel.getId()).orElseThrow().getDescription());
        assertEquals(Set.of(addedCategory.getId()), assignedIds());
    }

    private void performFailure(UpdateChannelRequest request, int httpStatus, int code) throws Exception {
        mvc.perform(patch(ENDPOINT).contextPath("/channel").cookie(cookie("CHANNEL_UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(request)))
                .andExpect(status().is(httpStatus)).andExpect(jsonPath("$.code").value(code));
        assertUnchanged();
    }

    private void assertUnchanged() {
        Map<String, Object> saved = jdbc.queryForMap(
                "SELECT description, banner_url FROM channels WHERE id = ?", channel.getId());
        assertEquals("Original description", saved.get("description"));
        assertEquals("https://example.com/original.png", saved.get("banner_url"));
        assertEquals(Set.of(retainedCategory.getId(), removedCategory.getId()), assignedIds());
    }

    private Set<UUID> assignedIds() {
        return associations.findAllById_ChannelId(channel.getId()).stream()
                .map(association -> association.getId().getCategoryId()).collect(Collectors.toSet());
    }

    private ChannelEntity createChannel(UUID streamerId) {
        return channels.saveAndFlush(ChannelEntity.builder().streamerId(streamerId)
                .name("Original channel").slug("channel-" + streamerId).description("Original description")
                .avatarUrl("https://example.com/avatar.png").bannerUrl("https://example.com/original.png")
                .status(ChannelStatus.ACTIVE).build());
    }

    private CategoryEntity createCategory(CategoryStatus categoryStatus) {
        String identifier = UUID.randomUUID().toString();
        return categories.saveAndFlush(CategoryEntity.builder().name(identifier).slug(identifier).status(categoryStatus).build());
    }

    private ChannelCategoryEntity link(CategoryEntity category) {
        return ChannelCategoryEntity.builder().id(new ChannelCategoryId(channel.getId(), category.getId()))
                .channel(channel).category(category).build();
    }

    private UpdateChannelRequest validRequest() {
        return request("Updated description", "https://example.com/new.png", retainedCategory.getId(), addedCategory.getId());
    }

    private UpdateChannelRequest request(String description, String bannerUrl, UUID... categoryIds) {
        return UpdateChannelRequest.builder().description(description).bannerUrl(bannerUrl).categoryIds(List.of(categoryIds)).build();
    }

    private String json(UpdateChannelRequest request) {
        return mapper.writeValueAsString(request);
    }

    private Cookie cookie(String scope) throws Exception {
        return cookie(scope, userId.toString());
    }

    private Cookie cookie(String scope, String subject) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject(subject).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900))).claim("scope", scope).build();
        var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signed.sign(new MACSigner("channel-update-test-secret-".repeat(4)));
        return new Cookie("access_token", signed.serialize());
    }

    private static Stream<String> invalidRequests() {
        String categoryIds = "[\"" + UUID.randomUUID() + "\"]";
        return Stream.of(
                "{}", "{\"description\":null,\"bannerUrl\":\"\",\"categoryIds\":" + categoryIds + "}",
                "{\"description\":\"\",\"bannerUrl\":null,\"categoryIds\":" + categoryIds + "}",
                "{\"description\":\"\",\"bannerUrl\":\"\",\"categoryIds\":null}",
                "{\"description\":\"\",\"bannerUrl\":\"\",\"categoryIds\":[]}",
                "{\"description\":\"\",\"bannerUrl\":\"\",\"categoryIds\":[null]}",
                "{\"description\":\"\",\"bannerUrl\":\"\",\"categoryIds\":[\"invalid\"]}",
                "{\"description\":\"\",\"bannerUrl\":\" \",\"categoryIds\":" + categoryIds + "}",
                "{\"description\":\"\",\"bannerUrl\":\"ftp://example.com/banner.png\",\"categoryIds\":" + categoryIds + "}",
                "{\"description\":\"" + "a".repeat(1001) + "\",\"bannerUrl\":\"\",\"categoryIds\":" + categoryIds + "}");
    }
}
