package com.reallive.paymentservice.wallet.repository;

import com.reallive.paymentservice.wallet.entity.UserWalletEntity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=validate")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserWalletRepositoryTest {

    private static final String SCHEMA = "wallet_repository_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private UserWalletRepository walletRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("wallet.test.database-url",
                "jdbc:h2:mem:wallet_repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username", () -> System.getProperty("wallet.test.database-username", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("wallet.test.database-password", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "\"" + SCHEMA + "\"");
    }

    @AfterAll
    static void dropTestSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void migrationIsValidAndDoesNotReapply() {
        flyway.validate();
        assertEquals("1", flyway.info().current().getVersion().toString());
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void persistsExternalUserIdWithGeneratedWalletIdZeroBalanceAndTimestamps() {
        UUID userId = UUID.randomUUID();
        UserWalletEntity saved = walletRepository.saveAndFlush(UserWalletEntity.builder().userId(userId).build());
        UserWalletEntity reloaded = walletRepository.findByUserId(userId).orElseThrow();

        assertNotNull(saved.getId());
        assertEquals(saved.getId(), reloaded.getId());
        assertEquals(userId, reloaded.getUserId());
        assertEquals(0L, reloaded.getBalance());
        assertNotNull(reloaded.getCreatedAt());
        assertNotNull(reloaded.getUpdatedAt());
        assertTrue(walletRepository.existsByUserId(userId));
    }

    @Test
    void databaseGeneratesIdBalanceAndTimestampsWhenOnlyUserIdIsProvided() {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + walletTable() + " (user_id) VALUES (?)", userId);

        UserWalletEntity wallet = walletRepository.findByUserId(userId).orElseThrow();
        assertNotNull(wallet.getId());
        assertEquals(0L, wallet.getBalance());
        assertNotNull(wallet.getCreatedAt());
        assertNotNull(wallet.getUpdatedAt());
    }

    @Test
    void databaseRejectsDuplicateUserWithoutChangingExistingBalance() {
        UUID userId = UUID.randomUUID();
        UserWalletEntity saved = walletRepository.saveAndFlush(UserWalletEntity.builder()
                .userId(userId).balance(500L).build());

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO " + walletTable() + " (user_id) VALUES (?)", userId));

        assertEquals(1, countWallets(userId));
        UserWalletEntity existing = walletRepository.findByUserId(userId).orElseThrow();
        assertEquals(saved.getId(), existing.getId());
        assertEquals(500L, existing.getBalance());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, Long.MIN_VALUE})
    void databaseRejectsNegativeBalance(long balance) {
        UUID userId = UUID.randomUUID();

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO " + walletTable() + " (user_id, balance) VALUES (?, ?)", userId, balance));

        assertEquals(0, countWallets(userId));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void databaseRejectsNullRequiredColumns(int columnIndex) {
        Object[] columns = {UUID.randomUUID(), UUID.randomUUID(), 0L, LocalDateTime.now(), LocalDateTime.now()};
        columns[columnIndex] = null;

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO " + walletTable()
                        + " (id, user_id, balance, created_at, updated_at) VALUES (?, ?, ?, ?, ?)", columns));
    }

    @Test
    void updatesBalanceWithoutChangingWalletIdentityOrCreationTimestamp() {
        UUID userId = UUID.randomUUID();
        walletRepository.saveAndFlush(UserWalletEntity.builder().userId(userId).build());
        UserWalletEntity wallet = walletRepository.findByUserId(userId).orElseThrow();
        LocalDateTime createdAt = wallet.getCreatedAt();

        wallet.setBalance(750L);
        walletRepository.saveAndFlush(wallet);

        UserWalletEntity reloaded = walletRepository.findByUserId(userId).orElseThrow();
        assertEquals(wallet.getId(), reloaded.getId());
        assertEquals(userId, reloaded.getUserId());
        assertEquals(750L, reloaded.getBalance());
        assertEquals(createdAt, reloaded.getCreatedAt());
        assertFalse(reloaded.getUpdatedAt().isBefore(createdAt));
    }

    @Test
    void returnsEmptyForUserWithoutWallet() {
        UUID userId = UUID.randomUUID();

        assertTrue(walletRepository.findByUserId(userId).isEmpty());
        assertFalse(walletRepository.existsByUserId(userId));
    }

    @Test
    void concurrentInsertsCannotCreateTwoWalletsForSameUser() throws Exception {
        UUID userId = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> insertConcurrently(userId, ready, start));
            var second = executor.submit(() -> insertConcurrently(userId, ready, start));
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                int successfulInserts = (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                        + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
                assertEquals(1, successfulInserts);
            } finally {
                start.countDown();
            }
        }

        assertEquals(1, countWallets(userId));
    }

    private boolean insertConcurrently(UUID userId, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
                    walletRepository.saveAndFlush(UserWalletEntity.builder().userId(userId).build()));
            return true;
        } catch (DataIntegrityViolationException exception) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent wallet inserts");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrent wallet inserts", exception);
        }
    }

    private int countWallets(UUID userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + walletTable() + " WHERE user_id = ?", Integer.class, userId);
    }

    private String walletTable() {
        return "\"" + SCHEMA + "\".user_wallets";
    }
}
