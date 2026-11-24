package com.reallive.paymentservice.wallet.controller.external;

import com.reallive.paymentservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import com.reallive.paymentservice.wallet.repository.UserWalletRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class WalletCreationIntegrationTest {
    private static final String CONTEXT = "/payment";
    private static final String WALLET_PATH = CONTEXT + "/api/wallets/me";
    private static final String LEGACY_PATH = CONTEXT + "/internal/api/wallets/create/";
    @MockitoBean
    private RedisAuthoritiesReader authAuthoritiesReader;

    @BeforeEach
    void setUpAuthAuthorities() {
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static final String SCHEMA = "wallet_api_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserWalletRepository repository;
    private MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("wallet.test.database-url",
                "jdbc:h2:mem:wallet_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username", () -> System.getProperty("wallet.test.database-username", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("wallet.test.database-password", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "\"" + SCHEMA + "\"");
    }

    @AfterAll
    static void dropSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousRequestsCannotCreateWallets() throws Exception {
        long count = repository.count();
        mvc.perform(post(WALLET_PATH).contextPath(CONTEXT))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
        mvc.perform(post(LEGACY_PATH + UUID.randomUUID()).contextPath(CONTEXT))
                .andExpect(status().isUnauthorized());
        assertEquals(count, repository.count());
    }

    @Test
    void onlyAuthenticatedUserGetsWalletEvenIfRequestSuppliesAnotherUserId() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        Cookie cookie = accessCookie(userId);

        mvc.perform(get(WALLET_PATH).contextPath(CONTEXT).cookie(cookie))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1006));
        assertTrue(repository.findByUserId(userId).isEmpty());

        mvc.perform(post(WALLET_PATH).contextPath(CONTEXT).cookie(cookie)
                        .param("userId", otherUserId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.userId").value(userId.toString()))
                .andExpect(jsonPath("$.result.balance").value(0));

        UserWalletEntity wallet = repository.findByUserId(userId).orElseThrow();
        assertNotNull(wallet.getCreatedAt());
        assertNotNull(wallet.getUpdatedAt());
        assertTrue(repository.findByUserId(otherUserId).isEmpty());
        mvc.perform(get(WALLET_PATH).contextPath(CONTEXT).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.walletId").value(wallet.getId().toString()));
    }

    @Test
    void legacyCreationEndpointIsGoneEvenForAuthenticatedUsers() throws Exception {
        UUID userId = UUID.randomUUID();
        mvc.perform(post(LEGACY_PATH + userId).contextPath(CONTEXT).cookie(accessCookie(userId)))
                .andExpect(status().isNotFound());
        assertTrue(repository.findByUserId(userId).isEmpty());
    }

    @Test
    void repeatRequestsReturnExistingWalletAndPreserveCoins() throws Exception {
        UUID userId = UUID.randomUUID();
        UserWalletEntity wallet = repository.saveAndFlush(UserWalletEntity.builder()
                .userId(userId).balance(1200L).build());

        mvc.perform(post(WALLET_PATH).contextPath(CONTEXT).cookie(accessCookie(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.walletId").value(wallet.getId().toString()))
                .andExpect(jsonPath("$.result.balance").value(1200));

        UserWalletEntity reloaded = repository.findByUserId(userId).orElseThrow();
        assertEquals(wallet.getCreatedAt(), reloaded.getCreatedAt());
        assertEquals(wallet.getUpdatedAt(), reloaded.getUpdatedAt());
    }

    @Test
    void concurrentRequestsReturnSameWallet() throws Exception {
        UUID userId = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createConcurrently(userId, ready, start));
            var second = executor.submit(() -> createConcurrently(userId, ready, start));
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
        }
        assertEquals(1, repository.findAll().stream().filter(wallet -> userId.equals(wallet.getUserId())).count());
    }

    private String createConcurrently(UUID userId, CountDownLatch ready, CountDownLatch start) throws Exception {
        Cookie cookie = accessCookie(userId);
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out starting concurrent wallet requests");
        }
        String response = mvc.perform(post(WALLET_PATH).contextPath(CONTEXT).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.walletId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return JsonMapper.shared().readTree(response).path("result").path("walletId").asString();
    }

    private static Cookie accessCookie(UUID userId) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject(userId.toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        token.sign(new MACSigner("wallet-api-test-key-".repeat(4)));
        return new Cookie("access_token", token.serialize());
    }
}
