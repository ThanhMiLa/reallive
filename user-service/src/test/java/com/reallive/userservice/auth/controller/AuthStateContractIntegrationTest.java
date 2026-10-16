package com.reallive.userservice.auth.controller;

import com.nimbusds.jwt.SignedJWT;
import com.reallive.userservice.auth.adapter.redis.AuthStateBackfill;
import com.reallive.userservice.auth.adapter.redis.AuthStateSyncWorker;
import com.reallive.userservice.auth.adapter.redis.RedisAuthStateStore;
import com.reallive.userservice.auth.dto.request.AuthRequest;
import com.reallive.userservice.auth.entity.RoleEntity;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.AuthStateSyncTaskRepository;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.auth.service.AuthStateSyncService;
import com.reallive.userservice.auth.service.impl.AuthStateSyncProcessor;
import com.reallive.userservice.client.channel.ChannelClientResolver;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.entity.ProfileEntity;
import com.reallive.userservice.user.repository.ProfileRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_state_contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.data.redis.database=14", "auth.state.retry-enabled=false"
})
@ActiveProfiles("test")
@Import(AuthStateContractIntegrationTest.Endpoints.class)
@EnabledIfSystemProperty(named = "auth.test.redis-port", matches = "[0-9]+")
class AuthStateContractIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private ProfileRepository profiles;
    @Autowired private PasswordEncoder passwords;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthStateSyncTaskRepository tasks;
    @Autowired private AuthStateSyncService synchronization;
    @Autowired private AuthStateSyncProcessor processor;
    @Autowired private TransactionTemplate transactions;
    @MockitoBean private ChannelClientResolver channel;
    @MockitoSpyBean private RedisAuthStateStore stateStore;

    private final List<String> cleanup = new ArrayList<>();
    private MockMvc mvc;
    private UserEntity user;

    @DynamicPropertySource
    static void redisPort(DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.port", () -> System.getProperty("auth.test.redis-port"));
    }

    @BeforeEach
    void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("INSERT INTO permissions (id, name) SELECT ?, 'CHANNEL_UPDATE' WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'CHANNEL_UPDATE')", UUID.randomUUID());
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.name = 'STREAMER' AND p.name = 'CHANNEL_UPDATE' AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id)");
        var userRole = roles.findByName("USER").orElseGet(() -> roles.saveAndFlush(
                RoleEntity.builder().name("USER").build()));
        user = users.saveAndFlush(UserEntity.builder().username("auth-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@example.com").passwordHash(passwords.encode("secret-password"))
                .emailVerified(true).roles(new HashSet<>(Set.of(userRole))).build());
        profiles.saveAndFlush(ProfileEntity.builder().user(user).displayName("Auth contract").build());
        cleanup.addAll(List.of("auth:authorities:" + user.getId(), "auth:status:" + user.getId(),
                "auth:revision:" + user.getId()));
        when(channel.createChannel(any())).thenReturn(true);
    }

    @AfterEach
    void cleanupRedis() {
        redis.delete(cleanup);
    }

    @Test
    void sameUnscopedAccessTokenGetsNewPermissionImmediatelyAfterBecomeStreamer() throws Exception {
        MvcResult login = login();
        Cookie access = cookie(login, "access_token");
        assertNull(SignedJWT.parse(access.getValue()).getJWTClaimsSet().getClaim("scope"));
        mvc.perform(get("/test/auth/streamer").cookie(access)).andExpect(status().isForbidden());
        mvc.perform(post("/api/users/me/become-streamer").cookie(access)).andExpect(status().isOk());
        mvc.perform(get("/test/auth/streamer").cookie(access)).andExpect(status().isOk());
        assertTrue(redis.opsForValue().get("auth:authorities:" + user.getId()).contains("CHANNEL_UPDATE"));
        assertFalse(tasks.existsById(user.getId()));
        assertEquals(1, users.findById(user.getId()).orElseThrow().getAuthRevision());
    }

    @Test
    void failedPostCommitPublicationKeepsDurableTaskAndRetryRepairsState() throws Exception {
        Cookie access = cookie(login(), "access_token");
        doThrow(new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE)).when(stateStore).publish(any(), anyList(), eq(false));
        mvc.perform(post("/api/users/me/become-streamer").cookie(access))
                .andExpect(status().isServiceUnavailable());
        assertTrue(users.findWithAuthoritiesById(user.getId()).orElseThrow().getRoles().stream()
                .anyMatch(role -> role.getName().equals("STREAMER")));
        assertTrue(tasks.existsById(user.getId()));
        assertEquals(1, tasks.findById(user.getId()).orElseThrow().getAttempts());
        mvc.perform(get("/test/auth/streamer").cookie(access)).andExpect(status().isForbidden());
        doCallRealMethod().when(stateStore).publish(any(), anyList(), eq(false));
        jdbc.update("UPDATE auth_state_sync_tasks SET next_attempt_at = ? WHERE user_id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), user.getId());
        new AuthStateSyncWorker(tasks, processor).retryPending();
        assertFalse(tasks.existsById(user.getId()));
        mvc.perform(get("/test/auth/streamer").cookie(access)).andExpect(status().isOk());
    }

    @Test
    void businessRollbackDoesNotPublishRoleOrLeaveSyncTask() throws Exception {
        Cookie access = cookie(login(), "access_token");
        doThrow(new AppException(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE)).when(channel).createChannel(any());
        mvc.perform(post("/api/users/me/become-streamer").cookie(access)).andExpect(status().is5xxServerError());
        assertFalse(tasks.existsById(user.getId()));
        assertFalse(users.findWithAuthoritiesById(user.getId()).orElseThrow().getRoles().stream()
                .anyMatch(role -> role.getName().equals("STREAMER")));
        mvc.perform(get("/test/auth/streamer").cookie(access)).andExpect(status().isForbidden());
    }

    @Test
    void banAndUnbanHookSynchronizesStatusAndCentralIssuancePolicy() throws Exception {
        login();
        changeStatus(UserStatus.BANNED);
        assertEquals("BANNED", redis.opsForValue().get("auth:status:" + user.getId()));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials()))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        changeStatus(UserStatus.ACTIVE);
        assertEquals("ACTIVE", redis.opsForValue().get("auth:status:" + user.getId()));
        login();
    }

    @Test
    void refreshRebuildsBothKeysAndFailureDoesNotConsumeTheOldToken() throws Exception {
        Cookie refresh = cookie(login(), "refresh_token");
        String revocationKey = "auth:revoked:refresh:" + SignedJWT.parse(refresh.getValue()).getJWTClaimsSet().getJWTID();
        cleanup.add(revocationKey);
        redis.expire("auth:authorities:" + user.getId(), Duration.ofSeconds(5));
        redis.delete("auth:status:" + user.getId());
        doThrow(new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE)).when(stateStore).publish(any(), anyList(), eq(true));
        mvc.perform(post("/api/auth/refresh").cookie(refresh)).andExpect(status().isServiceUnavailable());
        assertFalse(redis.hasKey(revocationKey));
        doCallRealMethod().when(stateStore).publish(any(), anyList(), eq(true));
        mvc.perform(post("/api/auth/refresh").cookie(refresh)).andExpect(status().isOk());
        assertEquals("ACTIVE", redis.opsForValue().get("auth:status:" + user.getId()));
        assertTrue(redis.getExpire("auth:authorities:" + user.getId()) > 2591900);
        assertTrue(redis.getExpire("auth:status:" + user.getId()) > 2591900);
        mvc.perform(post("/api/auth/refresh").cookie(refresh)).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedRequestWithMissingAuthoritiesFails503AndPublicLoginCanRepairIt() throws Exception {
        Cookie access = cookie(login(), "access_token");
        redis.delete("auth:authorities:" + user.getId());
        mvc.perform(get("/test/auth/streamer").cookie(access)).andExpect(status().isServiceUnavailable());
        login();
        mvc.perform(get("/test/auth/streamer").cookie(access)).andExpect(status().isForbidden());
    }

    @Test
    void controlledBackfillPublishesExistingStateWithoutIssuingTokens() {
        new AuthStateBackfill(users, processor).run(new DefaultApplicationArguments());
        assertEquals("ACTIVE", redis.opsForValue().get("auth:status:" + user.getId()));
        assertEquals("[\"ROLE_USER\"]", redis.opsForValue().get("auth:authorities:" + user.getId()));
        assertFalse(tasks.existsById(user.getId()));
    }

    @Test
    void rolePermissionChangesGrantAndRevokePermissionWithoutReplacingTheJwt() throws Exception {
        RoleEntity role = roles.saveAndFlush(RoleEntity.builder().name("DYNAMIC_" + UUID.randomUUID())
                .permissions(new HashSet<>()).build());
        transactions.executeWithoutResult(transaction -> {
            var account = users.findByIdForUpdate(user.getId()).orElseThrow();
            account.getRoles().clear();
            account.getRoles().add(role);
        });
        Cookie access = cookie(login(), "access_token");
        mvc.perform(get("/test/auth/streamer").cookie(access)).andExpect(status().isForbidden());
        UUID permissionId = jdbc.queryForObject("SELECT id FROM permissions WHERE name = 'CHANNEL_UPDATE'", UUID.class);
        transactions.executeWithoutResult(transaction -> {
            jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)", role.getId(), permissionId);
            synchronization.rolePermissionsChanged(role.getId());
        });
        mvc.perform(get("/test/auth/streamer").cookie(access)).andExpect(status().isOk());
        transactions.executeWithoutResult(transaction -> {
            jdbc.update("DELETE FROM role_permissions WHERE role_id = ? AND permission_id = ?", role.getId(), permissionId);
            synchronization.rolePermissionsChanged(role.getId());
        });
        mvc.perform(get("/test/auth/streamer").cookie(access)).andExpect(status().isForbidden());
        assertEquals(2, users.findById(user.getId()).orElseThrow().getAuthRevision());
    }

    private MvcResult login() throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials()))
                .andExpect(status().isOk()).andReturn();
    }

    private String credentials() {
        return mapper.writeValueAsString(AuthRequest.builder().username(user.getUsername()).password("secret-password").build());
    }

    private Cookie cookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1, header.indexOf(';')));
    }

    private void changeStatus(UserStatus status) {
        transactions.executeWithoutResult(transaction -> {
            var account = users.findByIdForUpdate(user.getId()).orElseThrow();
            account.setStatus(status);
            synchronization.changed(account);
        });
    }

    @RestController
    static class Endpoints {
        @GetMapping("/test/auth/streamer")
        @PreAuthorize("hasAuthority('CHANNEL_UPDATE')")
        String streamerOnly() {
            return "allowed";
        }
    }
}
