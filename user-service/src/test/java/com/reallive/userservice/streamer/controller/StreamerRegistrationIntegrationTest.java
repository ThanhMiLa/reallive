package com.reallive.userservice.streamer.controller;

import com.reallive.userservice.auth.entity.RoleEntity;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.client.channel.ChannelHttpTestServer;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.security.CustomJwtDecoder;
import com.reallive.userservice.streamer.entity.StreamerEntity;
import com.reallive.userservice.streamer.entity.StreamerStatus;
import com.reallive.userservice.streamer.repository.StreamerRepository;
import com.reallive.userservice.user.entity.ProfileEntity;
import com.reallive.userservice.user.repository.ProfileRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.open-in-view=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.cloud.openfeign.client.config.channel-service.connectTimeout=500",
        "spring.cloud.openfeign.client.config.channel-service.readTimeout=500"
})
@ActiveProfiles("test")
class StreamerRegistrationIntegrationTest {

    private static final String ENDPOINT = "/api/users/me/become-streamer";
    private static final String SCHEMA = "streamer_registration_" + UUID.randomUUID().toString().replace("-", "");
    private static final ChannelHttpTestServer CHANNEL = new ChannelHttpTestServer();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository users;
    @Autowired
    private RoleRepository roles;
    @Autowired
    private StreamerRepository streamers;
    @MockitoSpyBean
    private ProfileRepository profiles;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    @MockitoBean
    private CustomJwtDecoder decoder;

    private MockMvc mvc;
    private UserEntity user;
    private RoleEntity streamerRole;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("app.service.channel", CHANNEL::baseUrl);
        registry.add("spring.datasource.url", () -> System.getProperty("streamer.test.database-url",
                "jdbc:h2:mem:streamer_registration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username", () -> System.getProperty("streamer.test.database-username", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("streamer.test.database-password", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "\"" + SCHEMA + "\"");
    }

    @AfterAll
    static void dropTestSchema(@Autowired JdbcTemplate jdbc) {
        CHANNEL.close();
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @AfterEach
    void releaseDelayedReply() {
        CHANNEL.releaseResponse();
    }

    @BeforeEach
    void setUp() {
        CHANNEL.reset();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        streamerRole = roles.findByName("STREAMER").orElseThrow();
        RoleEntity userRole = roles.findByName("USER").orElseGet(() -> roles.saveAndFlush(RoleEntity.builder().name("USER").build()));
        RoleEntity moderatorRole = roles.findByName("MODERATOR").orElseGet(() -> roles.saveAndFlush(RoleEntity.builder().name("MODERATOR").build()));
        user = users.saveAndFlush(UserEntity.builder().username("streamer-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@example.com").passwordHash("test-hash").emailVerified(true)
                .roles(new HashSet<>(Set.of(userRole, moderatorRole))).build());
        profiles.saveAndFlush(ProfileEntity.builder().user(user).displayName("Test streamer").build());
        authenticate(user.getId().toString());
    }

    @Test
    void registersOnlyAuthenticatedUserFromDatabaseAndRejectsRepeatWithStaleJwt() throws Exception {
        UUID permissionId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("permissions") + " (id, name) VALUES (?, ?)", permissionId, "STREAMER_TEST_PERMISSION");
        jdbc.update("INSERT INTO " + table("role_permissions") + " (role_id, permission_id) VALUES (?, ?)", streamerRole.getId(), permissionId);
        UUID otherUserId = users.saveAndFlush(UserEntity.builder().username("other-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@example.com").passwordHash("other-hash").emailVerified(true).build()).getId();
        var result = mvc.perform(post(ENDPOINT).cookie(cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + otherUserId + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.result.status").value("ACTIVE"))
                .andExpect(jsonPath("$.result.roles", containsInAnyOrder("USER", "MODERATOR", "STREAMER")))
                .andExpect(jsonPath("$.timestamp").exists()).andReturn();
        var streamer = streamers.findByUserId(user.getId()).orElseThrow();
        assertEquals(streamer.getId().toString(), mapper.readTree(result.getResponse().getContentAsString())
                .path("result").path("streamerId").asString());
        assertEquals(StreamerStatus.ACTIVE, streamer.getStatus());
        assertNotNull(streamer.getVerifiedAt());
        var channelRequest = CHANNEL.takeRequest();
        var channelPayload = mapper.readTree(channelRequest.body());
        assertEquals(streamer.getId().toString(), channelPayload.path("streamerId").asString());
        assertEquals("Test streamer", channelPayload.path("name").asString());
        assertTrue(channelPayload.path("avatarUrl").isNull() || channelPayload.path("avatarUrl").isMissingNode());
        assertEquals("/channel/internal/api/channels", channelRequest.path());
        assertTrue(CHANNEL.hasChannel(streamer.getId()));
        assertEquals(1, streamerCount(user.getId()));
        assertEquals(0, streamerCount(otherUserId));
        assertEquals(3, roleCount(user.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + table("user_roles") + " ur JOIN "
                + table("role_permissions") + " rp ON ur.role_id = rp.role_id WHERE ur.user_id = ? AND rp.permission_id = ?",
                Integer.class, user.getId(), permissionId));
        mvc.perform(post(ENDPOINT).cookie(cookie())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1030));
        assertEquals(1, streamerCount(user.getId()));
        assertEquals(3, roleCount(user.getId()));
        assertEquals(1, CHANNEL.callCount());
    }

    @ParameterizedTest
    @EnumSource(value = ChannelHttpTestServer.Mode.class,
            names = {"FALSE_RESULT", "NULL_RESULT", "EMPTY_BODY", "INVALID_JSON", "LOST_RESPONSE"})
    void invalidOrLostChannelResponseRollsBackStreamerAndRole(ChannelHttpTestServer.Mode mode) throws Exception {
        CHANNEL.setMode(mode);
        var original = snapshot();

        mvc.perform(post(ENDPOINT).cookie(cookie())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(1037));

        assertEquals(original, snapshot());
        assertEquals(0, streamerCount(user.getId()));
        assertEquals(1, CHANNEL.callCount());
        if (mode == ChannelHttpTestServer.Mode.LOST_RESPONSE || mode == ChannelHttpTestServer.Mode.INVALID_JSON) {
            UUID orphanStreamerId = UUID.fromString(mapper.readTree(CHANNEL.takeRequest().body()).path("streamerId").asString());
            assertTrue(CHANNEL.hasChannel(orphanStreamerId));
            assertTrue(streamers.findById(orphanStreamerId).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 500, 503})
    void channelHttpFailureReturns1037AndRollsBackRegistrationWithoutRetry(int status) throws Exception {
        CHANNEL.setErrorStatus(status);
        var original = snapshot();

        mvc.perform(post(ENDPOINT).cookie(cookie())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500)).andExpect(jsonPath("$.code").value(1037))
                .andExpect(jsonPath("$.message").value("Channel service unavailable"));

        assertEquals(original, snapshot());
        assertEquals(0, streamerCount(user.getId()));
        assertEquals(1, CHANNEL.callCount());
    }

    @Test
    void retryAfterLostResponseCreatesNewStreamerWhilePreviousChannelRemainsOrphaned() throws Exception {
        CHANNEL.setMode(ChannelHttpTestServer.Mode.LOST_RESPONSE);
        mvc.perform(post(ENDPOINT).cookie(cookie())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(1037));
        UUID orphanStreamerId = UUID.fromString(mapper.readTree(CHANNEL.takeRequest().body()).path("streamerId").asString());
        CHANNEL.releaseResponse();
        CHANNEL.setMode(ChannelHttpTestServer.Mode.SUCCESS);

        mvc.perform(post(ENDPOINT).cookie(cookie())).andExpect(status().isOk());

        UUID streamerId = streamers.findByUserId(user.getId()).orElseThrow().getId();
        assertNotEquals(orphanStreamerId, streamerId);
        assertTrue(streamers.findById(orphanStreamerId).isEmpty());
        assertTrue(CHANNEL.hasChannel(orphanStreamerId));
        assertTrue(CHANNEL.hasChannel(streamerId));
        assertEquals(1, streamerCount(user.getId()));
        assertEquals(3, roleCount(user.getId()));
        assertEquals(2, CHANNEL.callCount());
    }

    @Test
    void localCommitFailureRollsBackRegistrationAfterSuccessfulRemoteSave() throws Exception {
        var repositoryAnswer = mockingDetails(profiles).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object profile = repositoryAnswer.answer(invocation);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("Simulated local commit failure");
                }
            });
            return profile;
        }).when(profiles).findByUserId(user.getId());
        var original = snapshot();

        assertThrows(ServletException.class, () -> mvc.perform(post(ENDPOINT).cookie(cookie())));

        UUID orphanStreamerId = UUID.fromString(mapper.readTree(CHANNEL.takeRequest().body()).path("streamerId").asString());
        assertEquals(original, snapshot());
        assertEquals(0, streamerCount(user.getId()));
        assertTrue(CHANNEL.hasChannel(orphanStreamerId));
        assertTrue(streamers.findById(orphanStreamerId).isEmpty());
        assertEquals(1, CHANNEL.callCount());
    }

    @Test
    void missingProfileReturnsProfileErrorAndRollsBackRegistration() throws Exception {
        profiles.delete(profiles.findByUserId(user.getId()).orElseThrow());
        var original = snapshot();

        mvc.perform(post(ENDPOINT).cookie(cookie())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.PROFILE_NOT_FOUND.getCode()));

        assertEquals(original, snapshot());
        assertEquals(0, streamerCount(user.getId()));
        assertEquals(0, CHANNEL.callCount());

        profiles.saveAndFlush(ProfileEntity.builder().user(user).displayName("Restored profile").build());
        mvc.perform(post(ENDPOINT).cookie(cookie())).andExpect(status().isOk());
        assertEquals(1, streamerCount(user.getId()));
        assertEquals(3, roleCount(user.getId()));
        assertEquals(1, CHANNEL.callCount());
    }

    @ParameterizedTest
    @CsvSource({"banned, 403, 1003", "unverified, 403, 1027", "role-only, 409, 1030", "missing-user, 404, 1004"})
    void databaseEligibilityFailuresDoNotChangeUserOrCreateStreamer(String condition, int httpStatus, int code) throws Exception {
        switch (condition) {
            case "banned" -> user.setStatus(UserStatus.BANNED);
            case "unverified" -> user.setEmailVerified(false);
            case "role-only" -> user.getRoles().add(streamerRole);
            case "missing-user" -> authenticate(UUID.randomUUID().toString());
            default -> throw new AssertionError(condition);
        }
        users.saveAndFlush(user);
        var original = snapshot();
        mvc.perform(post(ENDPOINT).cookie(cookie())).andExpect(status().is(httpStatus)).andExpect(jsonPath("$.code").value(code));
        assertEquals(original, snapshot());
        assertEquals(0, streamerCount(user.getId()));
        assertEquals(0, CHANNEL.callCount());
    }

    @ParameterizedTest
    @EnumSource(StreamerStatus.class)
    void anyExistingStreamerRecordBlocksRegistrationWithoutRoleAndKeepsModerationData(StreamerStatus streamerStatus) throws Exception {
        var existing = streamers.saveAndFlush(StreamerEntity.builder().user(user).status(streamerStatus)
                .suspensionReason("Existing moderation history").build());
        var original = snapshot();
        mvc.perform(post(ENDPOINT).cookie(cookie())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1030));
        var reloaded = streamers.findByUserId(user.getId()).orElseThrow();
        assertEquals(existing.getId(), reloaded.getId());
        assertEquals(streamerStatus, reloaded.getStatus());
        assertEquals("Existing moderation history", reloaded.getSuspensionReason());
        assertEquals(existing.getUpdatedAt(), reloaded.getUpdatedAt());
        assertEquals(original, snapshot());
        assertEquals(1, streamerCount(user.getId()));
        assertEquals(0, CHANNEL.callCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "invalid-token", "invalid-subject"})
    void unauthenticatedRequestsCannotChangeDatabase(String condition) throws Exception {
        if ("invalid-token".equals(condition)) {
            when(decoder.decode("registration-token")).thenThrow(new BadJwtException("invalid"));
        } else if ("invalid-subject".equals(condition)) {
            authenticate("not-a-uuid");
        }
        var request = post(ENDPOINT);
        if (!"anonymous".equals(condition)) {
            request.cookie(cookie());
        }
        var original = snapshot();
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
        assertEquals(original, snapshot());
        assertEquals(0, streamerCount(user.getId()));
        assertEquals(0, CHANNEL.callCount());
    }

    @Test
    void missingSeededRoleDoesNotMutateUser() throws Exception {
        jdbc.update("DELETE FROM " + table("role_permissions") + " WHERE role_id = ?", streamerRole.getId());
        jdbc.update("DELETE FROM " + table("user_roles") + " WHERE role_id = ?", streamerRole.getId());
        roles.deleteById(streamerRole.getId());
        try {
            var original = snapshot();
            mvc.perform(post(ENDPOINT).cookie(cookie())).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(1031));
            assertEquals(original, snapshot());
            assertEquals(0, streamerCount(user.getId()));
            assertEquals(0, CHANNEL.callCount());
        } finally {
            roles.saveAndFlush(RoleEntity.builder().name("STREAMER").build());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_roles", "streamers"})
    void databaseFailureDuringEitherWriteRollsBackBothChanges(String failedTable) {
        String constraint = "reject_registration_" + user.getId().toString().replace("-", "");
        String predicate = "user_id <> '" + user.getId() + "'";
        if ("user_roles".equals(failedTable)) {
            predicate += " OR role_id <> '" + streamerRole.getId() + "'";
        }
        jdbc.execute("ALTER TABLE " + table(failedTable) + " ADD CONSTRAINT " + constraint + " CHECK (" + predicate + ")");
        try {
            var original = snapshot();
            assertThrows(ServletException.class, () -> mvc.perform(post(ENDPOINT).cookie(cookie())));
            assertEquals(original, snapshot());
            assertEquals(0, streamerCount(user.getId()));
        } finally {
            jdbc.execute("ALTER TABLE " + table(failedTable) + " DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    void concurrentRequestsCommitExactlyOneRegistration() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> registerAfterBarrier(ready, start));
            var second = executor.submit(() -> registerAfterBarrier(ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(List.of(200, 409), List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .stream().sorted().toList());
        } finally {
            start.countDown();
        }
        assertEquals(1, streamerCount(user.getId()));
        assertEquals(3, roleCount(user.getId()));
        assertEquals(StreamerStatus.ACTIVE, streamers.findByUserId(user.getId()).orElseThrow().getStatus());
        assertEquals(1, CHANNEL.callCount());
    }

    private int registerAfterBarrier(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        var response = mvc.perform(post(ENDPOINT).cookie(cookie())).andReturn().getResponse();
        assertEquals(response.getStatus() == 200 ? 1000 : 1030, mapper.readTree(response.getContentAsString()).path("code").asInt());
        return response.getStatus();
    }

    private void authenticate(String subject) {
        when(decoder.decode("registration-token")).thenReturn(Jwt.withTokenValue("registration-token").header("alg", "HS512")
                .subject(subject).claim("scope", "STREAMER").claim("emailVerified", false).claim("status", "BANNED").build());
    }

    private Cookie cookie() {
        return new Cookie("access_token", "registration-token");
    }

    private Object snapshot() {
        return List.of(jdbc.queryForMap("SELECT status, email_verified, updated_at FROM " + table("users") + " WHERE id = ?", user.getId()),
                jdbc.queryForList("SELECT role_id FROM " + table("user_roles") + " WHERE user_id = ? ORDER BY role_id", UUID.class, user.getId()));
    }

    private int roleCount(UUID userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table("user_roles") + " WHERE user_id = ?", Integer.class, userId);
    }

    private int streamerCount(UUID userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table("streamers") + " WHERE user_id = ?", Integer.class, userId);
    }

    private String table(String name) {
        return "\"" + SCHEMA + "\"." + name;
    }
}
