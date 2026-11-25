package com.reallive.paymentservice.wallet.repository;

import com.reallive.paymentservice.wallet.entity.StreamerWalletEntity;
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
class StreamerWalletRepositoryTest {

    private static final String SCHEMA = "streamer_wallet_repo_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private StreamerWalletRepository streamerWalletRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("wallet.test.database-url",
                "jdbc:h2:mem:streamer_wallet_repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
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
        assertEquals("5", flyway.info().current().getVersion().toString());
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void persistsStreamerIdWithGeneratedWalletIdZeroBalanceAndTimestamps() {
        UUID streamerId = UUID.randomUUID();
        StreamerWalletEntity saved = streamerWalletRepository.saveAndFlush(
                StreamerWalletEntity.builder().streamerId(streamerId).build());
        StreamerWalletEntity reloaded = streamerWalletRepository.findByStreamerId(streamerId).orElseThrow();

        assertNotNull(saved.getId());
        assertEquals(saved.getId(), reloaded.getId());
        assertEquals(streamerId, reloaded.getStreamerId());
        assertEquals(0L, reloaded.getAvailableBalance());
        assertNotNull(reloaded.getCreatedAt());
        assertNotNull(reloaded.getUpdatedAt());
    }

    @Test
    void databaseGeneratesIdBalanceAndTimestampsWhenOnlyStreamerIdIsProvided() {
        UUID streamerId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + walletTable() + " (streamer_id) VALUES (?)", streamerId);

        StreamerWalletEntity wallet = streamerWalletRepository.findByStreamerId(streamerId).orElseThrow();
        assertNotNull(wallet.getId());
        assertEquals(0L, wallet.getAvailableBalance());
        assertNotNull(wallet.getCreatedAt());
        assertNotNull(wallet.getUpdatedAt());
    }

    @Test
    void insertIfAbsentIsIdempotentAndPreservesExistingBalanceAndTimestamps() {
        UUID streamerId = UUID.randomUUID();
        StreamerWalletEntity existing = streamerWalletRepository.saveAndFlush(StreamerWalletEntity.builder()
                .streamerId(streamerId)
                .availableBalance(1500L)
                .build());

        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> streamerWalletRepository.insertIfAbsent(streamerId));

        assertEquals(1, countWallets(streamerId));
        StreamerWalletEntity reloaded = streamerWalletRepository.findByStreamerId(streamerId).orElseThrow();
        assertEquals(existing.getId(), reloaded.getId());
        assertEquals(1500L, reloaded.getAvailableBalance());
        assertEquals(existing.getCreatedAt(), reloaded.getCreatedAt());
        assertEquals(existing.getUpdatedAt(), reloaded.getUpdatedAt());
    }

    @Test
    void creditAvailableBalanceUpdatesBalanceAndUpdatedAt() {
        UUID streamerId = UUID.randomUUID();
        StreamerWalletEntity saved = streamerWalletRepository.saveAndFlush(
                StreamerWalletEntity.builder().streamerId(streamerId).build());
        LocalDateTime updatedAt = saved.getUpdatedAt().plusMinutes(5);

        int updatedRows = new TransactionTemplate(transactionManager).execute(status ->
                streamerWalletRepository.creditAvailableBalance(saved.getId(), 750L, updatedAt));

        assertEquals(1, updatedRows);
        StreamerWalletEntity reloaded = streamerWalletRepository.findByStreamerId(streamerId).orElseThrow();
        assertEquals(saved.getId(), reloaded.getId());
        assertEquals(streamerId, reloaded.getStreamerId());
        assertEquals(750L, reloaded.getAvailableBalance());
        assertEquals(saved.getCreatedAt(), reloaded.getCreatedAt());
        assertFalse(reloaded.getUpdatedAt().isBefore(updatedAt));
    }

    @Test
    void creditAvailableBalanceReturnsZeroWhenWalletDoesNotExist() {
        int updatedRows = new TransactionTemplate(transactionManager).execute(status ->
                streamerWalletRepository.creditAvailableBalance(UUID.randomUUID(), 200L, LocalDateTime.now()));

        assertEquals(0, updatedRows);
    }

    @Test
    void databaseRejectsDuplicateStreamerWithoutChangingExistingBalance() {
        UUID streamerId = UUID.randomUUID();
        StreamerWalletEntity saved = streamerWalletRepository.saveAndFlush(StreamerWalletEntity.builder()
                .streamerId(streamerId)
                .availableBalance(500L)
                .build());

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO " + walletTable() + " (streamer_id) VALUES (?)", streamerId));

        assertEquals(1, countWallets(streamerId));
        StreamerWalletEntity existing = streamerWalletRepository.findByStreamerId(streamerId).orElseThrow();
        assertEquals(saved.getId(), existing.getId());
        assertEquals(500L, existing.getAvailableBalance());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, Long.MIN_VALUE})
    void databaseRejectsNegativeAvailableBalance(long balance) {
        UUID streamerId = UUID.randomUUID();

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO " + walletTable() + " (streamer_id, available_balance) VALUES (?, ?)",
                        streamerId, balance));

        assertEquals(0, countWallets(streamerId));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void databaseRejectsNullRequiredColumns(int columnIndex) {
        Object[] columns = {UUID.randomUUID(), UUID.randomUUID(), 0L, LocalDateTime.now(), LocalDateTime.now()};
        columns[columnIndex] = null;

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO " + walletTable()
                        + " (id, streamer_id, available_balance, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                        columns));
    }

    @Test
    void returnsEmptyForStreamerWithoutWallet() {
        assertTrue(streamerWalletRepository.findByStreamerId(UUID.randomUUID()).isEmpty());
    }

    @Test
    void concurrentInsertIfAbsentCreatesSingleWalletWithoutFailure() throws Exception {
        UUID streamerId = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> insertIfAbsentConcurrently(streamerId, ready, start));
            var second = executor.submit(() -> insertIfAbsentConcurrently(streamerId, ready, start));
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
            } finally {
                start.countDown();
            }
        }

        assertEquals(1, countWallets(streamerId));
    }

    @Test
    void concurrentEntityInsertsCannotCreateTwoWalletsForSameStreamer() throws Exception {
        UUID streamerId = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> insertEntityConcurrently(streamerId, ready, start));
            var second = executor.submit(() -> insertEntityConcurrently(streamerId, ready, start));
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

        assertEquals(1, countWallets(streamerId));
    }

    private Void insertIfAbsentConcurrently(UUID streamerId, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> streamerWalletRepository.insertIfAbsent(streamerId));
        return null;
    }

    private boolean insertEntityConcurrently(UUID streamerId, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    streamerWalletRepository.saveAndFlush(StreamerWalletEntity.builder().streamerId(streamerId).build()));
            return true;
        } catch (DataIntegrityViolationException exception) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent streamer wallet inserts");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrent streamer wallet inserts", exception);
        }
    }

    private int countWallets(UUID streamerId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + walletTable() + " WHERE streamer_id = ?",
                Integer.class,
                streamerId
        );
    }

    private String walletTable() {
        return "\"" + SCHEMA + "\".streamer_wallets";
    }
}
