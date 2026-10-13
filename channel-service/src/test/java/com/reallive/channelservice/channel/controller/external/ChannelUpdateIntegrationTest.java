package com.reallive.channelservice.channel.controller.external;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.channelservice.common.service.R2StorageService;
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
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
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

import javax.imageio.ImageIO;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.context-path=/channel",
        "spring.datasource.url=jdbc:h2:mem:channel_update;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.cloud.openfeign.client.config.user-service.connectTimeout=300",
        "spring.cloud.openfeign.client.config.user-service.readTimeout=300",
        "server.tomcat.max-swallow-size=10MB"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChannelUpdateIntegrationTest {

    private static final String ENDPOINT = "/channel/api/channels/me";
    private static final StreamerHttpTestServer USER_SERVICE = new StreamerHttpTestServer();

    @Autowired
    private MockMvc mvc;
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

    @MockitoBean
    private R2StorageService storage;

    @Value("${local.server.port}")
    private int port;

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
        when(storage.getPublicUrl(anyString())).thenAnswer(invocation -> "https://images.example.com/" + invocation.getArgument(0));
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
        var request = form(validRequest())
                .param("categoryIds", addedCategory.getId().toString())
                .param("streamerId", otherChannel.getStreamerId().toString())
                .param("channelId", otherChannel.getId().toString());

        mvc.perform(request.cookie(cookie("CHANNEL_UPDATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.id").value(channel.getId().toString()))
                .andExpect(jsonPath("$.result.streamerId").value(channel.getStreamerId().toString()))
                .andExpect(jsonPath("$.result.name").value("Original channel"))
                .andExpect(jsonPath("$.result.slug").value(channel.getSlug()))
                .andExpect(jsonPath("$.result.avatarUrl").value(channel.getAvatarUrl()))
                .andExpect(jsonPath("$.result.description").value("Updated description"))
                .andExpect(jsonPath("$.result.bannerUrl").value(startsWith("https://images.example.com/channels/" + channel.getId() + "/banners/")))
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
        assertTrue(saved.getBannerUrl().startsWith("https://images.example.com/channels/" + channel.getId() + "/banners/"));
        verify(storage).upload(anyString(), any(byte[].class), eq("image/png"));
        verify(storage).deleteManagedBanner(channel.getBannerUrl(), channel.getId());
        assertEquals("Original description", channels.findById(otherChannel.getId()).orElseThrow().getDescription());
    }

    @Test
    void descriptionOnlyPreservesBannerAndExistingCategoriesWithoutRevalidatingThem() throws Exception {
        retainedCategory.setStatus(CategoryStatus.INACTIVE);
        categories.saveAndFlush(retainedCategory);
        var existingLinks = jdbc.queryForList("SELECT * FROM channel_categories WHERE channel_id = ? ORDER BY category_id", channel.getId());

        mvc.perform(form(UpdateChannelRequest.builder().description("Description only").build())
                        .cookie(cookie("CHANNEL_UPDATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.description").value("Description only"))
                .andExpect(jsonPath("$.result.bannerUrl").value(channel.getBannerUrl()))
                .andExpect(jsonPath("$.result.categoryIds", containsInAnyOrder(
                        retainedCategory.getId().toString(), removedCategory.getId().toString())));

        ChannelEntity saved = channels.findById(channel.getId()).orElseThrow();
        assertEquals("Description only", saved.getDescription());
        assertEquals(channel.getBannerUrl(), saved.getBannerUrl());
        assertEquals(existingLinks, jdbc.queryForList("SELECT * FROM channel_categories WHERE channel_id = ? ORDER BY category_id", channel.getId()));
        verifyNoInteractions(storage);
    }

    @Test
    void bannerOnlyPreservesDescriptionAndCategories() throws Exception {
        mvc.perform(form(UpdateChannelRequest.builder().bannerUrl(banner()).build()).cookie(cookie("CHANNEL_UPDATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.description").value(channel.getDescription()))
                .andExpect(jsonPath("$.result.bannerUrl").value(startsWith("https://images.example.com/channels/" + channel.getId() + "/banners/")))
                .andExpect(jsonPath("$.result.categoryIds", containsInAnyOrder(
                        retainedCategory.getId().toString(), removedCategory.getId().toString())));

        ChannelEntity saved = channels.findById(channel.getId()).orElseThrow();
        assertEquals(channel.getDescription(), saved.getDescription());
        assertTrue(saved.getBannerUrl().startsWith("https://images.example.com/channels/" + channel.getId() + "/banners/"));
        assertEquals(Set.of(retainedCategory.getId(), removedCategory.getId()), assignedIds());
        verify(storage).upload(anyString(), any(byte[].class), eq("image/png"));
        verify(storage).deleteManagedBanner(channel.getBannerUrl(), channel.getId());
    }

    @Test
    void categoriesOnlyPreservesDescriptionAndBanner() throws Exception {
        mvc.perform(form(UpdateChannelRequest.builder().categoryIds(List.of(addedCategory.getId())).build())
                        .cookie(cookie("CHANNEL_UPDATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.description").value(channel.getDescription()))
                .andExpect(jsonPath("$.result.bannerUrl").value(channel.getBannerUrl()))
                .andExpect(jsonPath("$.result.categoryIds", containsInAnyOrder(addedCategory.getId().toString())));

        ChannelEntity saved = channels.findById(channel.getId()).orElseThrow();
        assertEquals(channel.getDescription(), saved.getDescription());
        assertEquals(channel.getBannerUrl(), saved.getBannerUrl());
        assertEquals(Set.of(addedCategory.getId()), assignedIds());
        verifyNoInteractions(storage);
    }

    @Test
    void omittedFieldsLeaveProfileAndCategoryTimestampsUnchanged() throws Exception {
        var existingChannel = jdbc.queryForMap("SELECT * FROM channels WHERE id = ?", channel.getId());
        var existingLinks = jdbc.queryForList("SELECT * FROM channel_categories WHERE channel_id = ? ORDER BY category_id", channel.getId());

        mvc.perform(form(UpdateChannelRequest.builder().build()).cookie(cookie("CHANNEL_UPDATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.description").value(channel.getDescription()))
                .andExpect(jsonPath("$.result.bannerUrl").value(channel.getBannerUrl()))
                .andExpect(jsonPath("$.result.categoryIds", containsInAnyOrder(
                        retainedCategory.getId().toString(), removedCategory.getId().toString())));

        assertEquals(existingChannel, jdbc.queryForMap("SELECT * FROM channels WHERE id = ?", channel.getId()));
        assertEquals(existingLinks, jdbc.queryForList("SELECT * FROM channel_categories WHERE channel_id = ? ORDER BY category_id", channel.getId()));
        verifyNoInteractions(storage);
    }

    @Test
    void requiresAuthenticationBeforeCallingUserService() throws Exception {
        mvc.perform(form(validRequest()))
                .andExpect(status().isUnauthorized());
        assertTrue(USER_SERVICE.requests().isEmpty());
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ROLE_STREAMER", "ROLE_ADMIN", "CHANNEL_READ"})
    void roleOrOtherAuthorityCannotReplaceTheUpdatePermission(String scope) throws Exception {
        mvc.perform(form(validRequest()).cookie(cookie(scope)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        assertTrue(USER_SERVICE.requests().isEmpty());
        assertUnchanged();
    }

    @Test
    void malformedAuthenticatedUserIdCannotReachUserService() throws Exception {
        mvc.perform(form(validRequest()).cookie(cookie("CHANNEL_UPDATE", "invalid-user-id")))
                .andExpect(status().isUnauthorized());
        assertTrue(USER_SERVICE.requests().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidDtoNeverCallsUserServiceOrChangesDatabase(MockMultipartHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.cookie(cookie("CHANNEL_UPDATE")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1004));
        assertTrue(USER_SERVICE.requests().isEmpty());
        assertUnchanged();
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @CsvSource({"404,404,1006", "403,403,1007", "401,503,1008", "500,503,1008", "503,503,1008"})
    void upstreamRejectionStopsTheUpdate(int upstreamStatus, int expectedStatus, int expectedCode) throws Exception {
        USER_SERVICE.reply(upstreamStatus, "{\"status\":" + upstreamStatus + ",\"result\":null}");
        mvc.perform(form(validRequest()).cookie(cookie("CHANNEL_UPDATE")))
                .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(expectedCode));
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{}", "{\"status\":200,\"code\":1000,\"result\":null}",
            "{\"status\":200,\"code\":1000,\"result\":{}}",
            "{\"status\":200,\"code\":1000,\"result\":{\"streamerId\":\"invalid-uuid\"}}"})
    void invalidUpstreamSuccessBodyStopsTheUpdate(String body) throws Exception {
        USER_SERVICE.reply(200, body);
        mvc.perform(form(validRequest()).cookie(cookie("CHANNEL_UPDATE")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1008));
        assertUnchanged();
    }

    @Test
    void upstreamTimeoutStopsTheUpdate() throws Exception {
        USER_SERVICE.delay(1000);
        mvc.perform(form(validRequest()).cookie(cookie("CHANNEL_UPDATE")))
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
        performFailure(request("Updated", banner(), retainedCategory.getId(), UUID.randomUUID()), 400, 1011);
    }

    @Test
    void inactiveCategoryRejectsTheEntireUpdate() throws Exception {
        CategoryEntity inactive = createCategory(CategoryStatus.INACTIVE);
        performFailure(request("Updated", banner(), retainedCategory.getId(), inactive.getId()), 400, 1012);
    }

    @Test
    void databaseConstraintFailureRollsBackProfileAndCategoryReplacement() throws Exception {
        jdbc.execute("ALTER TABLE channels ADD CONSTRAINT ck_channel_update_test "
                + "CHECK (description IS NULL OR description <> 'reject-at-commit')");
        try {
            performFailure(request("reject-at-commit", banner(),
                    retainedCategory.getId(), addedCategory.getId()), 503, 1003);
            var objectKey = ArgumentCaptor.forClass(String.class);
            verify(storage).upload(objectKey.capture(), any(byte[].class), eq("image/png"));
            verify(storage).deleteQuietly(objectKey.getValue());
            verify(storage, never()).deleteManagedBanner(anyString(), any());
        } finally {
            jdbc.execute("ALTER TABLE channels DROP CONSTRAINT ck_channel_update_test");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg", "webp"})
    void detectsImageFormatFromContentInsteadOfFilenameAndMimeType(String format) throws Exception {
        byte[] bytes;
        if (format.equals("webp")) {
            bytes = Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAfQ//73v/+BiOh/AAA=");
        } else {
            var output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB), format, output);
            bytes = output.toByteArray();
        }
        var file = new MockMultipartFile("bannerUrl", "../../fake.txt", "text/plain", bytes);
        mvc.perform(form(request("Updated", file, retainedCategory.getId())).cookie(cookie("CHANNEL_UPDATE")))
                .andExpect(status().isOk());
        var key = ArgumentCaptor.forClass(String.class);
        verify(storage).upload(key.capture(), eq(bytes), eq(format.equals("jpg") ? "image/jpeg" : "image/" + format));
        assertTrue(key.getValue().startsWith("channels/" + channel.getId() + "/banners/"));
        assertTrue(key.getValue().endsWith("." + format));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-image", "<svg xmlns='http://www.w3.org/2000/svg'></svg>"})
    void rejectsInvalidImageContentBeforeUploading(String content) throws Exception {
        var file = new MockMultipartFile("bannerUrl", "banner.png", "image/png", content.getBytes(StandardCharsets.UTF_8));
        performFailure(request("Updated", file, retainedCategory.getId()), 400, 1013);
        verify(storage, never()).upload(anyString(), any(byte[].class), anyString());
    }

    @Test
    void rejectsOversizedFilesWithoutUploading() throws Exception {
        var file = new MockMultipartFile("bannerUrl", "banner.png", "image/png", new byte[5 * 1024 * 1024 + 1]);
        performFailure(request("Updated", file, retainedCategory.getId()), 413, 1014);
        verify(storage, never()).upload(anyString(), any(byte[].class), anyString());
    }

    @Test
    void uploadFailurePreservesOldBannerAndCleansPotentialNewUpload() throws Exception {
        doThrow(new AppException(ErrorCode.BANNER_UPLOAD_FAILED))
                .when(storage).upload(anyString(), any(byte[].class), anyString());
        performFailure(validRequest(), 502, 1015);
        var key = ArgumentCaptor.forClass(String.class);
        verify(storage).upload(key.capture(), any(byte[].class), anyString());
        verify(storage).deleteQuietly(key.getValue());
        verify(storage, never()).deleteManagedBanner(anyString(), any());
    }

    @Test
    void cleanupFailureDoesNotFailACommittedUpdate() throws Exception {
        doThrow(new IllegalStateException("Cleanup unavailable")).when(storage)
                .deleteManagedBanner(channel.getBannerUrl(), channel.getId());
        mvc.perform(form(validRequest()).cookie(cookie("CHANNEL_UPDATE")))
                .andExpect(status().isOk());
        assertEquals("Updated description", channels.findById(channel.getId()).orElseThrow().getDescription());
        verify(storage, never()).deleteQuietly(anyString());
    }

    @Test
    void oversizedConfiguredPublicUrlStopsBeforeUpload() throws Exception {
        when(storage.getPublicUrl(anyString())).thenReturn("https://example.com/" + "a".repeat(500));
        performFailure(validRequest(), 503, 1016);
        verify(storage, never()).upload(anyString(), any(byte[].class), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/x-www-form-urlencoded", "text/plain", "application/octet-stream"})
    void unsupportedRequestFormatsReturnTheStandardEnvelopeWithoutUpdatingData(String contentType) throws Exception {
        mvc.perform(patch(ENDPOINT).contextPath("/channel").cookie(cookie("CHANNEL_UPDATE"))
                        .contentType(contentType).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").value(1017))
                .andExpect(jsonPath("$.message").value("Unsupported media type"))
                .andExpect(jsonPath("$.result").value(nullValue()))
                .andExpect(jsonPath("$.timestamp").isString());
        assertTrue(USER_SERVICE.requests().isEmpty());
        verifyNoInteractions(storage);
        assertUnchanged();
    }

    @Test
    void actualHttpPatchParsesMultipartFilesAndRepeatedCategoryFields() throws Exception {
        HttpResponse<String> response = sendMultipart(banner().getBytes());
        assertEquals(200, response.statusCode());
        var result = new ObjectMapper().readTree(response.body()).path("result");
        assertEquals("HTTP description", result.path("description").asString());
        assertTrue(result.path("bannerUrl").asString().startsWith("https://images.example.com/channels/"));
        assertEquals(Set.of(retainedCategory.getId(), addedCategory.getId()), assignedIds());
    }

    @Test
    void actualHttpMultipartParserRejectsFileOverFiveMegabytes() throws Exception {
        HttpResponse<String> response = sendMultipart(new byte[5 * 1024 * 1024 + 1]);
        assertEquals(413, response.statusCode());
        assertEquals(1014, new ObjectMapper().readTree(response.body()).path("code").asInt());
        assertTrue(USER_SERVICE.requests().isEmpty());
        assertUnchanged();
        verify(storage, never()).upload(anyString(), any(byte[].class), anyString());
    }

    private HttpResponse<String> sendMultipart(byte[] bannerBytes) throws Exception {
        String boundary = "channel-banner-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        for (var field : List.of(Map.entry("description", "HTTP description"),
                Map.entry("categoryIds", retainedCategory.getId().toString()),
                Map.entry("categoryIds", addedCategory.getId().toString()))) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                    + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"bannerUrl\"; filename=\"banner.png\""
                + "\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bannerBytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", "access_token=" + cookie("CHANNEL_UPDATE").getValue())
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
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
                    request("First", null, retainedCategory.getId())));
            try {
                assertTrue(firstLocked.await(5, TimeUnit.SECONDS));
                var second = executor.submit(() -> service.updateChannel(userId,
                        request("Second", null, addedCategory.getId())));
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
        mvc.perform(form(request).cookie(cookie("CHANNEL_UPDATE")))
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
        return request("Updated description", banner(), retainedCategory.getId(), addedCategory.getId());
    }

    private UpdateChannelRequest request(String description, MultipartFile bannerUrl, UUID... categoryIds) {
        return UpdateChannelRequest.builder().description(description).bannerUrl(bannerUrl).categoryIds(List.of(categoryIds)).build();
    }

    private MockMultipartHttpServletRequestBuilder form(UpdateChannelRequest request) throws IOException {
        var builder = multipart(HttpMethod.PATCH, ENDPOINT).contextPath("/channel");
        if (request.getBannerUrl() != null) {
            builder.file(new MockMultipartFile("bannerUrl", request.getBannerUrl().getOriginalFilename(),
                    request.getBannerUrl().getContentType(), request.getBannerUrl().getBytes()));
        }
        if (request.getDescription() != null) {
            builder.param("description", request.getDescription());
        }
        if (request.getCategoryIds() != null) {
            request.getCategoryIds().forEach(id -> builder.param("categoryIds", id.toString()));
        }
        return builder;
    }

    private static MockMultipartFile emptyBanner() {
        return new MockMultipartFile("bannerUrl", "", "application/octet-stream", new byte[0]);
    }

    private static MockMultipartFile banner() {
        try {
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB), "png", bytes);
            return new MockMultipartFile("bannerUrl", "banner.png", "image/png", bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
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

    private static Stream<MockMultipartHttpServletRequestBuilder> invalidRequests() {
        String categoryId = UUID.randomUUID().toString();
        return Stream.of(
                multipart(HttpMethod.PATCH, ENDPOINT).param("description", ""),
                multipart(HttpMethod.PATCH, ENDPOINT).param("description", " \t\n"),
                multipart(HttpMethod.PATCH, ENDPOINT).param("description", "a".repeat(1001)),
                multipart(HttpMethod.PATCH, ENDPOINT).file(emptyBanner()),
                multipart(HttpMethod.PATCH, ENDPOINT).param("categoryIds", ""),
                multipart(HttpMethod.PATCH, ENDPOINT).param("categoryIds", "invalid"),
                multipart(HttpMethod.PATCH, ENDPOINT).param("categoryIds", "null"),
                multipart(HttpMethod.PATCH, ENDPOINT).param("categoryIds", categoryId, ""),
                multipart(HttpMethod.PATCH, ENDPOINT).param("categoryIds[1]", categoryId),
                multipart(HttpMethod.PATCH, ENDPOINT).param("bannerUrl", "https://example.com/banner.png")
        ).map(builder -> builder.contextPath("/channel"));
    }
}
