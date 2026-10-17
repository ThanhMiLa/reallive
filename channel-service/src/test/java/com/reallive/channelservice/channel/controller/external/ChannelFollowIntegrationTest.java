package com.reallive.channelservice.channel.controller.external;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.channelservice.channel.repository.ChannelRepository;
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
import org.springframework.util.LinkedCaseInsensitiveMap;

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
    private ChannelRepository channels;
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
        insertFollow(channelId, otherUser);
        insertFollow(otherChannel, userId);
        Map<String, Object> profile = channelRow(channelId);
        Map<String, Object> otherProfile = channelRow(otherChannel);

        mvc.perform(authenticatedRequest("PUT", channelId, userId).param("userId", otherUser.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Channel followed successfully"))
                .andExpect(jsonPath("$.result").value(true))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
        assertEquals(1, membershipCount(channelId, userId));
        assertCounterChange(profile, channelId, 1);
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

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "BANNED"})
    void ownerCannotFollowOwnChannelAndReceivesConflictWithoutChangingData(String channelStatus) throws Exception {
        UUID userId = UUID.randomUUID();
        UUID channelId = insertChannel(channelStatus);
        jdbc.update("UPDATE " + table("channels") + " SET user_id = ? WHERE id = ?", userId, channelId);
        Map<String, Object> profile = channelRow(channelId);

        mvc.perform(authenticatedRequest("PUT", channelId, userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value(1021))
                .andExpect(jsonPath("$.message").value("Cannot follow own channel"))
                .andExpect(jsonPath("$.result").value(nullValue()));

        assertEquals(0, membershipCount(channelId, userId));
        assertEquals(profile, channelRow(channelId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "BANNED"})
    void ownerCanUnfollowALegacySelfFollowWithoutRemovingOtherFollowers(String channelStatus) throws Exception {
        UUID userId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID channelId = insertChannel(channelStatus);
        jdbc.update("UPDATE " + table("channels") + " SET user_id = ? WHERE id = ?", userId, channelId);
        insertFollow(channelId, userId);
        insertFollow(channelId, otherUser);
        Map<String, Object> profile = channelRow(channelId);

        mvc.perform(authenticatedRequest("DELETE", channelId, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(true));

        assertEquals(0, membershipCount(channelId, userId));
        assertEquals(1, membershipCount(channelId, otherUser));
        assertCounterChange(profile, channelId, -1);
    }

    @Test
    void duplicateFollowReturnsConflictAndPreservesTheOriginalTimestamp() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID channelId = insertChannel("ACTIVE");
        insertFollow(channelId, userId);
        Map<String, Object> profile = channelRow(channelId);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM " + table("channel_followers")
                + " WHERE channel_id = ? AND user_id = ?", channelId, userId);

        mvc.perform(authenticatedRequest("PUT", channelId, userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1019))
                .andExpect(jsonPath("$.result").value(nullValue()));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM " + table("channel_followers")
                + " WHERE channel_id = ? AND user_id = ?", channelId, userId));
        assertEquals(profile, channelRow(channelId));
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
        assertEquals(1L, followerCount(channelId));

        insertFollow(channelId, userId);
        mvc.perform(authenticatedRequest("DELETE", channelId, userId)).andExpect(status().isOk());
        mvc.perform(authenticatedRequest("DELETE", channelId, userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1020));
        assertEquals(1, membershipCount(channelId, otherUser));
        assertEquals(1L, followerCount(channelId));
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
        if (method.equals("DELETE")) {
            insertFollow(channelId, userId);
        }
        Map<String, Object> profile = channelRow(channelId);
        List<Integer> statuses = concurrentRequests(method, channelId, Collections.nCopies(8, userId));
        assertEquals(1, Collections.frequency(statuses, 200));
        assertEquals(7, Collections.frequency(statuses, 409));
        assertEquals(method.equals("PUT") ? 1 : 0, membershipCount(channelId, userId));
        assertCounterChange(profile, channelId, method.equals("PUT") ? 1 : -1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void differentUsersCanFollowAndUnfollowTheSameChannelConcurrentlyWithoutLosingUpdates(String method)
            throws Exception {
        UUID channelId = insertChannel("ACTIVE");
        List<UUID> users = IntStream.range(0, 8).mapToObj(index -> UUID.randomUUID()).toList();
        if (method.equals("DELETE")) {
            users.forEach(user -> insertFollow(channelId, user));
        }
        Map<String, Object> profile = channelRow(channelId);
        assertEquals(8, Collections.frequency(concurrentRequests(method, channelId, users), 200));
        for (UUID user : users) {
            assertEquals(method.equals("PUT") ? 1 : 0, membershipCount(channelId, user));
        }
        assertCounterChange(profile, channelId, method.equals("PUT") ? users.size() : -users.size());
    }

    @Test
    void simultaneousFollowsAndUnfollowsKeepTheCounterConsistent() throws Exception {
        UUID channelId = insertChannel("ACTIVE");
        List<UUID> users = IntStream.range(0, 8).mapToObj(index -> UUID.randomUUID()).toList();
        users.subList(0, 4).forEach(user -> insertFollow(channelId, user));
        List<String> methods = IntStream.range(0, users.size())
                .mapToObj(index -> index < 4 ? "DELETE" : "PUT").toList();
        Map<String, Object> profile = channelRow(channelId);

        assertEquals(8, Collections.frequency(concurrentRequests(methods, channelId, users), 200));

        for (int index = 0; index < users.size(); index++) {
            assertEquals(index < 4 ? 0 : 1, membershipCount(channelId, users.get(index)));
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
            assertEquals(1L, followerCount(channelId));
            status.setRollbackOnly();
        });
        assertEquals(0, membershipCount(channelId, userId));
        assertEquals(0L, followerCount(channelId));
        insertFollow(channelId, userId);
        transaction.executeWithoutResult(status -> {
            assertTrue(service.unfollowChannel(userId, channelId));
            assertEquals(0L, followerCount(channelId));
            status.setRollbackOnly();
        });
        assertEquals(1, membershipCount(channelId, userId));
        assertEquals(1L, followerCount(channelId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void counterUpdateFailureRollsBackTheFollowerChange(String method) throws Exception {
        UUID channelId = insertChannel("ACTIVE");
        UUID userId = UUID.randomUUID();
        if (method.equals("DELETE")) {
            insertFollow(channelId, userId);
        }
        Map<String, Object> profile = channelRow(channelId);
        String constraint = "ck_counter_failure_" + channelId.toString().replace("-", "");
        jdbc.execute("ALTER TABLE " + table("channels") + " ADD CONSTRAINT " + constraint
                + " CHECK (id <> '" + channelId + "' OR follower_count = " + followerCount(channelId) + ")");
        try {
            mvc.perform(authenticatedRequest(method, channelId, userId))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value(1003))
                    .andExpect(jsonPath("$.result").value(false));

            assertEquals(method.equals("PUT") ? 0 : 1, membershipCount(channelId, userId));
            assertEquals(profile, channelRow(channelId));
        } finally {
            jdbc.execute("ALTER TABLE " + table("channels") + " DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    void unfollowRejectsAnInconsistentZeroCounterAndRestoresTheFollow() throws Exception {
        UUID channelId = insertChannel("ACTIVE");
        UUID userId = UUID.randomUUID();
        insertFollow(channelId, userId);
        jdbc.update("UPDATE " + table("channels") + " SET follower_count = 0 WHERE id = ?", channelId);

        mvc.perform(authenticatedRequest("DELETE", channelId, userId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(1003));

        assertEquals(1, membershipCount(channelId, userId));
        assertEquals(0L, followerCount(channelId));
    }

    @Test
    void counterUpdatesClearPreviouslyLoadedChannelState() {
        UUID channelId = insertChannel("ACTIVE");
        UUID userId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            assertEquals(0L, channels.findById(channelId).orElseThrow().getFollowerCount());
            service.followChannel(userId, channelId);
            assertEquals(1L, channels.findById(channelId).orElseThrow().getFollowerCount());
            service.unfollowChannel(userId, channelId);
            assertEquals(0L, channels.findById(channelId).orElseThrow().getFollowerCount());
        });
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
        return concurrentRequests(Collections.nCopies(users.size(), method), channelId, users);
    }

    private List<Integer> concurrentRequests(List<String> methods, UUID channelId, List<UUID> users) throws Exception {
        CountDownLatch ready = new CountDownLatch(users.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(users.size())) {
            List<Future<Integer>> responses = new ArrayList<>();
            for (int index = 0; index < users.size(); index++) {
                UUID user = users.get(index);
                String method = methods.get(index);
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
        var response = mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString()))
                        .param("following", "true").param("keyword", channelId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(count));
        if (count > 0) {
            response.andExpect(jsonPath("$.result.content[0].numberOfFollower").value(followerCount(channelId)));
        }
    }

    private UUID insertChannel(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("channels")
                + " (id, streamer_id, name, slug, status, follower_count) VALUES (?, ?, ?, ?, ?, ?)",
                id, UUID.randomUUID(), "Channel " + id, id.toString(), status, 0L);
        return id;
    }

    private void insertFollow(UUID channelId, UUID userId) {
        jdbc.update("INSERT INTO " + table("channel_followers") + " (channel_id, user_id) VALUES (?, ?)",
                channelId, userId);
        jdbc.update("UPDATE " + table("channels") + " SET follower_count = follower_count + 1 WHERE id = ?", channelId);
    }

    private long followerCount(UUID channelId) {
        return jdbc.queryForObject("SELECT follower_count FROM " + table("channels") + " WHERE id = ?",
                Long.class, channelId);
    }

    private void assertCounterChange(Map<String, Object> before, UUID channelId, long delta) {
        Map<String, Object> expected = new LinkedCaseInsensitiveMap<>();
        expected.putAll(before);
        expected.put("follower_count", ((Number) before.get("follower_count")).longValue() + delta);
        assertEquals(expected, channelRow(channelId));
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
