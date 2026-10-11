package com.reallive.paymentservice.wallet.controller.internal;

import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import com.reallive.paymentservice.wallet.repository.UserWalletRepository;
import com.reallive.paymentservice.wallet.service.WalletService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class WalletCreationIntegrationTest {

    private static final String SCHEMA = "wallet_api_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserWalletRepository repository;
    @Autowired
    private WalletService service;
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
    void anonymousInternalPostCommitsZeroBalanceWalletWithCorrectUserId() throws Exception {
        UUID userId = UUID.randomUUID();
        mvc.perform(post(path(userId)).contextPath("/payment"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.userId").value(userId.toString()))
                .andExpect(jsonPath("$.result.balance").value(0));

        UserWalletEntity wallet = repository.findByUserId(userId).orElseThrow();
        assertNotNull(wallet.getId());
        assertEquals(0L, wallet.getBalance());
        assertNotNull(wallet.getCreatedAt());
        assertNotNull(wallet.getUpdatedAt());
    }

    @Test
    void repeatedRequestsCurrentlyReturn409AndPreserveExistingIdentityAndCoins() throws Exception {
        UUID userId = UUID.randomUUID();
        mvc.perform(post(path(userId)).contextPath("/payment")).andExpect(status().isOk());
        UserWalletEntity wallet = repository.findByUserId(userId).orElseThrow();
        wallet.setBalance(1200L);
        repository.saveAndFlush(wallet);

        for (int attempt = 0; attempt < 3; attempt++) {
            mvc.perform(post(path(userId)).contextPath("/payment"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1004));
        }

        UserWalletEntity reloaded = repository.findByUserId(userId).orElseThrow();
        assertEquals(wallet.getId(), reloaded.getId());
        assertEquals(1200L, reloaded.getBalance());
        assertEquals(wallet.getCreatedAt(), reloaded.getCreatedAt());
        assertEquals(1L, repository.findAll().stream().filter(w -> userId.equals(w.getUserId())).count());
    }

    @Test
    void differentUsersReceiveDifferentWallets() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var firstWallet = service.createWallet(first);
        var secondWallet = service.createWallet(second);

        assertNotEquals(firstWallet.getId(), secondWallet.getId());
        assertEquals(first, firstWallet.getUserId());
        assertEquals(second, secondWallet.getUserId());
        assertEquals(0L, firstWallet.getBalance());
        assertEquals(0L, secondWallet.getBalance());
    }

    @Test
    void nullUserIdCannotBeCommitted() {
        long count = repository.count();
        assertThrows(DataIntegrityViolationException.class, () -> service.createWallet(null));
        assertEquals(count, repository.count());
    }

    @Test
    void concurrentHttpRequestsCanCommitOnlyOneWallet() throws Exception {
        UUID userId = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createConcurrently(userId, ready, start));
            var second = executor.submit(() -> createConcurrently(userId, ready, start));
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                int firstStatus = first.get(10, TimeUnit.SECONDS);
                int secondStatus = second.get(10, TimeUnit.SECONDS);
                assertEquals(1, (firstStatus == 200 ? 1 : 0) + (secondStatus == 200 ? 1 : 0));
                int rejected = firstStatus == 200 ? secondStatus : firstStatus;
                assertTrue(rejected == 409 || rejected == 503, "Current conflict/constraint mapping: " + rejected);
            } finally {
                start.countDown();
            }
        }
        assertEquals(1L, repository.findAll().stream().filter(w -> userId.equals(w.getUserId())).count());
        assertEquals(0L, repository.findByUserId(userId).orElseThrow().getBalance());
    }

    private int createConcurrently(UUID userId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out starting concurrent wallet requests");
        }
        return mvc.perform(post(path(userId)).contextPath("/payment"))
                .andReturn().getResponse().getStatus();
    }

    private String path(UUID userId) {
        return "/payment/internal/api/wallets/create/" + userId;
    }
}
