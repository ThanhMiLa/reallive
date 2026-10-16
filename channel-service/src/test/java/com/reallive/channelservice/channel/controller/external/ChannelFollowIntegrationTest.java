package com.reallive.channelservice.channel.controller.external;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.channelservice.channel.service.ChannelFollowService;
import com.reallive.channelservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChannelFollowIntegrationTest {

    private static final String SCHEMA = "channel_follow_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PATH = "/channel/api/channels";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ChannelFollowService service;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private RedisAuthoritiesReader authoritiesReader;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("server.servlet.context-path", () -> "/channel");
        registry.add("spring.datasource.url", () -> System.getProperty("channel.test.database-url",
                "jdbc:h2:mem:channel_follow_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
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
    void setUpAuthentication() {
        when(authoritiesReader.read(any())).thenReturn(List.of());
    }

    @Test
    void followsAndUnfollowsOnlyTheRequestedChannelUsingTheAuthenticatedUser() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID channelId = insertChannel("ACTIVE");
        UUID otherChannel = insertChannel("ACTIVE");
        Map<String, Object> profile = channelRow(channelId);
        Map<String, Object> otherProfile = channelRow(otherChannel);
        insertFollow(channelId, otherUser);
        insertFollow(otherChannel, userId);

        mvc.perform(authenticatedRequest("PUT", channelId, userId).param("userId", otherUser.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Channel followed successfully"))
                .andExpect(jsonPath("$.result").value(true))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
        assertEquals(1, membershipCount(channelId, userId));
        assertNotNull(jdbc.queryForObject("SELECT followed_at FROM " + table("channel_followers")
                + " WHERE channel_id = ? AND user_id = ?", Object.class, channelId, userId));
        assertFollowingListContains(channelId, userId, 1);

        mvc.perform(authenticatedRequest("DELETE", channelId, userId).param("userId", otherUser.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Channel unfollowed successfully"))
                .andExpect(jsonPath("$.result").value(true));
        assertEquals(0, membershipCount(channelId, userId));
        assertEquals(1, membershipCount(channelId, otherUser));
        assertEquals(1, membershipCount(otherChannel, userId));
        assertEquals(profile, channelRow(channelId));
        assertEquals(otherProfile, channelRow(otherChannel));
        assertFollowingListContains(channelId, userId, 0);
    }

    @Test
    void duplicateFollowReturnsConflictAndPreservesTheOriginalTimestamp() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID channelId = insertChannel("ACTIVE");
        insertFollow(channelId, userId);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM " + table("channel_followers")
                + " WHERE channel_id = ? AND user_id = ?", channelId, userId);

        mvc.perform(authenticatedRequest("PUT", channelId, userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1019))
                .andExpect(jsonPath("$.result").value(nullValue()));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM " + table("channel_followers")
                + " WHERE channel_id = ? AND user_id = ?", channelId, userId));
    }

    @Test
    void missingFollowAndRepeatedUnfollowReturnConflictWithoutRemovingAnotherUsersFollow() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID channelId = insertChannel("ACTIVE");
        insertFollow(channelId, otherUser);
        mvc.perform(authenticatedRequest("DELETE", channelId, userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1020));
        assertEquals(1, membershipCount(channelId, otherUser));

        insertFollow(channelId, userId);
        mvc.perform(authenticatedRequest("DELETE", channelId, userId)).andExpect(status().isOk());
        mvc.perform(authenticatedRequest("DELETE", channelId, userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1020));
        assertEquals(1, membershipCount(channelId, otherUser));
    }

    @Test
    void bannedChannelRejectsFollowButAllowsUnfollow() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID channelId = insertChannel("BANNED");
        Map<String, Object> profile = channelRow(channelId);
        mvc.perform(authenticatedRequest("PUT", channelId, userId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1010));
        assertEquals(0, membershipCount(channelId, userId));
        insertFollow(channelId, userId);
        mvc.perform(authenticatedRequest("DELETE", channelId, userId)).andExpect(status().isOk());
        assertEquals(0, membershipCount(channelId, userId));
        assertEquals(profile, channelRow(channelId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void missingAndSoftDeletedChannelsReturnNotFound(String method) throws Exception {
        UUID userId = UUID.randomUUID();
        mvc.perform(authenticatedRequest(method, UUID.randomUUID(), userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1009));
        UUID deleted = insertChannel("ACTIVE");
        insertFollow(deleted, userId);
        jdbc.update("UPDATE " + table("channels") + " SET deleted_at = ? WHERE id = ?",
                LocalDateTime.now(), deleted);
        mvc.perform(authenticatedRequest(method, deleted, userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1009));
        assertEquals(1, membershipCount(deleted, userId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void requiresAuthenticationAndRejectsInvalidCredentials(String method) throws Exception {
        UUID channelId = insertChannel("ACTIVE");
        mvc.perform(followRequest(method, channelId.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
        for (Cookie cookie : List.of(new Cookie("access_token", "invalid-token"), accessCookie("not-a-uuid"))) {
            mvc.perform(followRequest(method, channelId.toString()).cookie(cookie))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(1001));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void rejectsMalformedAndMultipleChannelIds(String method) throws Exception {
        UUID userId = UUID.randomUUID();
        for (String value : List.of("not-a-uuid", UUID.randomUUID() + "," + UUID.randomUUID())) {
            mvc.perform(followRequest(method, value).cookie(accessCookie(userId.toString())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(1004));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void concurrentRequestsForOneUserHaveOneSuccessAndBusinessConflicts(String method) throws Exception {
        UUID channelId = insertChannel("ACTIVE");
        UUID userId = UUID.randomUUID();
        Map<String, Object> profile = channelRow(channelId);
        if (method.equals("DELETE")) {
            insertFollow(channelId, userId);
        }
        List<Integer> statuses = concurrentRequests(method, channelId, Collections.nCopies(8, userId));
        assertEquals(1, Collections.frequency(statuses, 200));
        assertEquals(7, Collections.frequency(statuses, 409));
        assertEquals(method.equals("PUT") ? 1 : 0, membershipCount(channelId, userId));
        assertEquals(profile, channelRow(channelId));
    }

    @Test
    void differentUsersCanFollowTheSameChannelConcurrentlyWithoutChangingTheChannel() throws Exception {
        UUID channelId = insertChannel("ACTIVE");
        Map<String, Object> profile = channelRow(channelId);
        List<UUID> users = IntStream.range(0, 8).mapToObj(index -> UUID.randomUUID()).toList();
        assertEquals(8, Collections.frequency(concurrentRequests("PUT", channelId, users), 200));
        for (UUID user : users) {
            assertEquals(1, membershipCount(channelId, user));
        }
        assertEquals(profile, channelRow(channelId));
    }

    @Test
    void followAndUnfollowJoinTheTransactionAndRollBackWithTheCaller() {
        UUID channelId = insertChannel("ACTIVE");
        UUID userId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            assertTrue(service.followChannel(userId, channelId));
            status.setRollbackOnly();
        });
        assertEquals(0, membershipCount(channelId, userId));
        insertFollow(channelId, userId);
        transaction.executeWithoutResult(status -> {
            assertTrue(service.unfollowChannel(userId, channelId));
            status.setRollbackOnly();
        });
        assertEquals(1, membershipCount(channelId, userId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void serviceRejectsMissingUserAndChannelIdentifiers(String method) {
        AppException missingUser = assertThrows(AppException.class,
                () -> invokeService(method, null, UUID.randomUUID()));
        assertEquals(ErrorCode.UNAUTHENTICATED, missingUser.getErrorCode());
        AppException missingChannel = assertThrows(AppException.class,
                () -> invokeService(method, UUID.randomUUID(), null));
        assertEquals(ErrorCode.CHANNEL_REQUEST_INVALID, missingChannel.getErrorCode());
    }

    private void invokeService(String method, UUID userId, UUID channelId) {
        if (method.equals("PUT")) {
            service.followChannel(userId, channelId);
        } else {
            service.unfollowChannel(userId, channelId);
        }
    }

    private List<Integer> concurrentRequests(String method, UUID channelId, List<UUID> users) throws Exception {
        CountDownLatch ready = new CountDownLatch(users.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(users.size())) {
            List<Future<Integer>> responses = new ArrayList<>();
            for (UUID user : users) {
                responses.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    var response = mvc.perform(authenticatedRequest(method, channelId, user));
                    int code = response.andReturn().getResponse().getStatus();
                    if (code == 409) {
                        response.andExpect(jsonPath("$.code").value(method.equals("PUT") ? 1019 : 1020));
                    }
                    return code;
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> response : responses) {
                statuses.add(response.get(20, TimeUnit.SECONDS));
            }
            return statuses;
        }
    }

    private MockHttpServletRequestBuilder authenticatedRequest(String method, UUID channelId, UUID userId)
            throws Exception {
        return followRequest(method, channelId.toString()).cookie(accessCookie(userId.toString()));
    }

    private MockHttpServletRequestBuilder followRequest(String method, String channelId) {
        return request(HttpMethod.valueOf(method), PATH + "/" + channelId + "/follow").contextPath("/channel");
    }

    private void assertFollowingListContains(UUID channelId, UUID userId, int count) throws Exception {
        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString()))
                        .param("following", "true").param("keyword", channelId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(count));
    }

    private UUID insertChannel(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("channels")
                + " (id, streamer_id, name, slug, status, follower_count) VALUES (?, ?, ?, ?, ?, ?)",
                id, UUID.randomUUID(), "Channel " + id, id.toString(), status, 123L);
        return id;
    }

    private void insertFollow(UUID channelId, UUID userId) {
        jdbc.update("INSERT INTO " + table("channel_followers") + " (channel_id, user_id) VALUES (?, ?)",
                channelId, userId);
    }

    private int membershipCount(UUID channelId, UUID userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table("channel_followers")
                + " WHERE channel_id = ? AND user_id = ?", Integer.class, channelId, userId);
    }

    private Map<String, Object> channelRow(UUID channelId) {
        return jdbc.queryForMap("SELECT * FROM " + table("channels") + " WHERE id = ?", channelId);
    }

    private String table(String name) {
        return "\"" + SCHEMA + "\"." + name;
    }

    private Cookie accessCookie(String subject) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject(subject).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300))).build();
        var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signed.sign(new MACSigner("channel-follow-test-secret-".repeat(4)));
        return new Cookie("access_token", signed.serialize());
    }
}
