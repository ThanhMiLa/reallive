package com.reallive.userservice.auth.adapter.redis;

import com.reallive.userservice.auth.entity.PermissionEntity;
import com.reallive.userservice.auth.entity.RoleEntity;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.AuthStateSyncTaskRepository;
import com.reallive.userservice.auth.repository.PermissionRepository;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.auth.service.impl.AuthStateSyncProcessor;
import com.reallive.userservice.auth.util.UserAuthoritiesMapper;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_backfill_regression;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch=true",
        "jwt.ttl_refreshToken=120",
        "auth.state.ttl-grace=15s"
})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({AuthStateSyncProcessor.class, UserAuthoritiesMapper.class, AuthStateBackfillIntegrationTest.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthStateBackfillIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private AuthStateSyncTaskRepository taskRepository;

    @Autowired
    private AuthStateSyncProcessor processor;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthStateStore authStateStore;

    private AuthStateBackfill backfill;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        taskRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        backfill = new AuthStateBackfill(userRepository, processor);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100, 101, 201})
    void publishesEachUserOnceWithTwoSelectsPerBatch(int userCount) {
        Map<UUID, AuthStateSnapshot> expected = createUsers(userCount);
        statistics.clear();

        backfill.run(new DefaultApplicationArguments());

        assertEquals(2L * ((userCount + 99) / 100), statistics.getPrepareStatementCount());
        assertEquals(0, statistics.getEntityInsertCount());
        assertEquals(0, statistics.getEntityUpdateCount());
        assertEquals(0, statistics.getEntityDeleteCount());
        assertPublishedSnapshots(expected);
        assertEquals(0, taskRepository.count());
    }

    @Test
    void emptyDatabaseOnlyQueriesIdsAndPublishesNothing() {
        backfill.run(new DefaultApplicationArguments());

        assertEquals(1, statistics.getPrepareStatementCount());
        verifyNoInteractions(authStateStore);
    }

    @Test
    void emptyBatchDoesNotQueryOrPublish() {
        processor.backfill(List.of());

        assertEquals(0, statistics.getPrepareStatementCount());
        verifyNoInteractions(authStateStore);
    }

    @Test
    void skipsUserDeletedBetweenIdLookupAndBatchLoad() {
        Map<UUID, AuthStateSnapshot> expected = createUsers(2);
        List<UUID> userIds = new ArrayList<>(expected.keySet());
        UUID deletedUserId = userIds.getFirst();
        userRepository.deleteById(deletedUserId);
        expected.remove(deletedUserId);
        statistics.clear();

        processor.backfill(userIds);

        assertEquals(1, statistics.getPrepareStatementCount());
        assertPublishedSnapshots(expected);
    }

    @Test
    void publicationFailureStopsBeforeLoadingTheNextBatch() {
        createUsers(101);
        AppException failure = new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE);
        doThrow(failure).when(authStateStore).publish(any(), anyList(), eq(false));
        statistics.clear();

        assertSame(failure, assertThrows(AppException.class,
                () -> backfill.run(new DefaultApplicationArguments())));

        assertEquals(2, statistics.getPrepareStatementCount());
        verify(authStateStore).publish(any(), eq(List.of()), eq(false));
        verifyNoMoreInteractions(authStateStore);
        assertEquals(0, taskRepository.count());
    }

    private Map<UUID, AuthStateSnapshot> createUsers(int userCount) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String suffix = UUID.randomUUID().toString();
            PermissionEntity readPermission = permissionRepository.save(PermissionEntity.builder()
                    .name("BACKFILL_READ_" + suffix).build());
            PermissionEntity writePermission = permissionRepository.save(PermissionEntity.builder()
                    .name("BACKFILL_WRITE_" + suffix).build());
            RoleEntity fullRole = roleRepository.save(RoleEntity.builder().name("BACKFILL_FULL_" + suffix)
                    .permissions(Set.of(readPermission, writePermission)).build());
            RoleEntity sharedRole = roleRepository.save(RoleEntity.builder().name("BACKFILL_SHARED_" + suffix)
                    .permissions(Set.of(readPermission)).build());
            RoleEntity emptyRole = roleRepository.save(RoleEntity.builder().name("BACKFILL_EMPTY_" + suffix).build());
            Map<UUID, AuthStateSnapshot> expected = new HashMap<>();
            for (int index = 0; index < userCount; index++) {
                Set<RoleEntity> roles = switch (index % 3) {
                    case 0 -> Set.of(fullRole, sharedRole, emptyRole);
                    case 1 -> Set.of(emptyRole);
                    default -> Set.of();
                };
                List<String> authorities = switch (index % 3) {
                    case 0 -> List.of(readPermission.getName(), writePermission.getName(),
                            "ROLE_" + emptyRole.getName(), "ROLE_" + fullRole.getName(), "ROLE_" + sharedRole.getName());
                    case 1 -> List.of("ROLE_" + emptyRole.getName());
                    default -> List.of();
                };
                UserEntity user = userRepository.save(UserEntity.builder().username(UUID.randomUUID().toString())
                        .email(UUID.randomUUID() + "@example.com").passwordHash("test-hash")
                        .status(index % 2 == 0 ? UserStatus.ACTIVE : UserStatus.BANNED)
                        .authRevision(index + 1L).roles(roles).build());
                expected.put(user.getId(), new AuthStateSnapshot(user.getId(), user.getAuthRevision(),
                        objectMapper.writeValueAsString(authorities), user.getStatus().name(), Duration.ofSeconds(135)));
            }
            return expected;
        });
    }

    private void assertPublishedSnapshots(Map<UUID, AuthStateSnapshot> expected) {
        ArgumentCaptor<AuthStateSnapshot> snapshots = ArgumentCaptor.forClass(AuthStateSnapshot.class);
        verify(authStateStore, times(expected.size())).publish(snapshots.capture(), eq(List.of()), eq(false));
        Map<UUID, AuthStateSnapshot> actual = snapshots.getAllValues().stream()
                .collect(Collectors.toMap(AuthStateSnapshot::userId, Function.identity()));
        assertEquals(expected, actual);
        verifyNoMoreInteractions(authStateStore);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
