package com.reallive.userservice.streamer.repository;

import com.reallive.userservice.auth.entity.RoleEntity;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.streamer.entity.StreamerEntity;
import com.reallive.userservice.streamer.entity.StreamerStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

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
class StreamerRepositoryTest {

    private static final String SCHEMA = "streamer_repository_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private StreamerRepository streamerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("streamer.test.database-url",
                "jdbc:h2:mem:streamer_repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username", () -> System.getProperty("streamer.test.database-username", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("streamer.test.database-password", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "\"" + SCHEMA + "\"");
    }

    @AfterAll
    static void dropTestSchema(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @ParameterizedTest
    @EnumSource(StreamerStatus.class)
    void persistsStatusAndModerationFields(StreamerStatus status) {
        UserEntity user = createUser();
        LocalDateTime verifiedAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        StreamerEntity streamer = StreamerEntity.builder()
                .user(user)
                .status(status)
                .verifiedAt(verifiedAt)
                .suspendedAt(verifiedAt.plusDays(1))
                .suspendedUntil(verifiedAt.plusDays(2))
                .suspensionReason("Moderation review")
                .build();

        StreamerEntity saved = streamerRepository.saveAndFlush(streamer);
        StreamerEntity reloaded = streamerRepository.findByUserId(user.getId()).orElseThrow();

        assertEquals(saved.getId(), reloaded.getId());
        assertEquals(user.getId(), reloaded.getUser().getId());
        assertEquals(status, reloaded.getStatus());
        assertEquals(verifiedAt, reloaded.getVerifiedAt());
        assertEquals(verifiedAt.plusDays(1), reloaded.getSuspendedAt());
        assertEquals(verifiedAt.plusDays(2), reloaded.getSuspendedUntil());
        assertEquals("Moderation review", reloaded.getSuspensionReason());
        assertNotNull(reloaded.getCreatedAt());
        assertNotNull(reloaded.getUpdatedAt());
    }

    @Test
    void findsBannedStreamerAfterRoleRemoval() {
        UserEntity user = createUser();
        streamerRepository.saveAndFlush(StreamerEntity.builder().user(user).status(StreamerStatus.BANNED).build());

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            UserEntity lockedUser = userRepository.findByIdForUpdate(user.getId()).orElseThrow();
            assertTrue(lockedUser.getRoles().isEmpty());
            assertEquals(StreamerStatus.BANNED,
                    streamerRepository.findByUserId(user.getId()).orElseThrow().getStatus());
        });
    }

    @Test
    void returnsEmptyForUserWithoutStreamer() {
        UUID missingId = UUID.randomUUID();
        assertTrue(streamerRepository.findByUserId(missingId).isEmpty());
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
                assertTrue(userRepository.findByIdForUpdate(missingId).isEmpty()));
    }

    @Test
    void duplicateStreamerRollsBackRoleGrant() {
        UserEntity user = createUser();
        streamerRepository.saveAndFlush(StreamerEntity.builder().user(user).status(StreamerStatus.BANNED).build());

        assertThrows(DataIntegrityViolationException.class, () ->
                new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                    UserEntity lockedUser = userRepository.findByIdForUpdate(user.getId()).orElseThrow();
                    lockedUser.getRoles().add(roleRepository.findByName("STREAMER").orElseThrow());
                    userRepository.flush();
                    streamerRepository.saveAndFlush(StreamerEntity.builder()
                            .user(lockedUser).status(StreamerStatus.ACTIVE).build());
                }));

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            assertTrue(userRepository.findById(user.getId()).orElseThrow().getRoles().isEmpty());
            assertEquals(StreamerStatus.BANNED,
                    streamerRepository.findByUserId(user.getId()).orElseThrow().getStatus());
        });
        assertEquals(1, countStreamers(user.getId()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "DISABLED")
    void databaseRejectsMissingOrUnknownStatus(String status) {
        UserEntity user = createUser();
        assertThrows(DataIntegrityViolationException.class, () -> insertStreamer(user.getId(), status));
        assertEquals(0, countStreamers(user.getId()));
    }

    @Test
    void databaseRejectsUnknownUser() {
        assertThrows(DataIntegrityViolationException.class, () -> insertStreamer(UUID.randomUUID(), "ACTIVE"));
    }

    @Test
    void addingStreamerRolePreservesExistingRoles() {
        UserEntity user = createUser();
        RoleEntity existingRole = roleRepository.saveAndFlush(RoleEntity.builder()
                .name("USER_" + UUID.randomUUID()).build());

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            UserEntity lockedUser = userRepository.findByIdForUpdate(user.getId()).orElseThrow();
            lockedUser.getRoles().add(existingRole);
            lockedUser.getRoles().add(roleRepository.findByName("STREAMER").orElseThrow());
        });

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            Set<String> roles = userRepository.findById(user.getId()).orElseThrow().getRoles().stream()
                    .map(RoleEntity::getName).collect(Collectors.toSet());
            assertEquals(Set.of(existingRole.getName(), "STREAMER"), roles);
        });
    }

    @Test
    void concurrentTransactionsWaitForSameUserAndReadCommittedRegistration() throws Exception {
        UserEntity user = createUser();
        UserEntity otherUser = createUser();
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                UserEntity lockedUser = userRepository.findByIdForUpdate(user.getId()).orElseThrow();
                firstLocked.countDown();
                await(releaseFirst);
                lockedUser.getRoles().add(roleRepository.findByName("STREAMER").orElseThrow());
                streamerRepository.saveAndFlush(StreamerEntity.builder()
                        .user(lockedUser).status(StreamerStatus.ACTIVE).build());
            }));
            try {
                assertTrue(firstLocked.await(5, TimeUnit.SECONDS));
                var second = executor.submit(() -> new TransactionTemplate(transactionManager).execute(transaction -> {
                    secondStarted.countDown();
                    UserEntity lockedUser = userRepository.findByIdForUpdate(user.getId()).orElseThrow();
                    assertTrue(lockedUser.getRoles().stream().anyMatch(role -> role.getName().equals("STREAMER")));
                    return streamerRepository.findByUserId(user.getId()).orElseThrow().getStatus();
                }));
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));

                new TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
                        assertNotNull(userRepository.findByIdForUpdate(otherUser.getId()).orElseThrow()));
                releaseFirst.countDown();
                first.get(5, TimeUnit.SECONDS);
                assertEquals(StreamerStatus.ACTIVE, second.get(5, TimeUnit.SECONDS));
            } finally {
                releaseFirst.countDown();
            }
        }
        assertEquals(1, countStreamers(user.getId()));
        assertFalse(streamerRepository.findByUserId(otherUser.getId()).isPresent());
    }

    private UserEntity createUser() {
        String identifier = UUID.randomUUID().toString();
        return userRepository.saveAndFlush(UserEntity.builder()
                .username(identifier).email(identifier + "@example.com")
                .passwordHash("test-hash").emailVerified(true).build());
    }

    private void insertStreamer(UUID userId, String status) {
        jdbc.update("INSERT INTO \"" + SCHEMA + "\".streamers (user_id, status) VALUES (?, ?)", userId, status);
    }

    private int countStreamers(UUID userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"" + SCHEMA + "\".streamers WHERE user_id = ?",
                Integer.class, userId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for transaction release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for transaction release", exception);
        }
    }
}
