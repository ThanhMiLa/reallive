package com.reallive.userservice.user.service.impl;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.client.channel.ChannelClientResolver;
import com.reallive.userservice.user.dto.request.UpdateCategoryPreferencesRequest;
import com.reallive.userservice.user.entity.UserCategoryPreferenceEntity;
import com.reallive.userservice.user.entity.UserCategoryPreferenceId;
import com.reallive.userservice.user.repository.UserCategoryPreferenceRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:category_query_regression;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CategoryPreferenceQueryIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCategoryPreferenceRepository preferenceRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private CategoryPreferenceServiceImpl service;
    private TransactionTemplate transactions;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        service = new CategoryPreferenceServiceImpl(userRepository, preferenceRepository,
                mock(ChannelClientResolver.class), transactions);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void replacementUsesOneUserSelectOneBulkDeleteAndOnlyRequiredInserts(int categoryCount) {
        UserEntity user = createUser();
        List<UUID> categoryIds = IntStream.range(0, categoryCount).mapToObj(index -> UUID.randomUUID()).toList();
        categoryIds.forEach(categoryId -> savePreference(user, categoryId));
        statistics.clear();

        var response = service.updateCategoryPreferences(user.getId(), request(categoryIds));

        assertEquals(categoryCount + 2, statistics.getPrepareStatementCount());
        assertEquals(1, statistics.getEntityLoadCount());
        assertEquals(categoryCount, statistics.getEntityInsertCount());
        assertEquals(0, statistics.getEntityDeleteCount());
        assertEquals(categoryIds, response.getCategoryIds());
        List<UserCategoryPreferenceEntity> preferences = preferenceRepository.findAllById_UserId(user.getId());
        assertEquals(categoryCount, preferences.size());
        preferences.forEach(preference -> {
            assertNotNull(preference.getCreatedAt());
            assertNotNull(preference.getUpdatedAt());
        });
    }

    @Test
    void failedReplacementRollsBackBothBulkDeleteAndNewInserts() {
        UserEntity user = createUser();
        UUID oldCategory = UUID.randomUUID();
        UUID newCategory = UUID.randomUUID();
        savePreference(user, oldCategory);

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            service.updateCategoryPreferences(user.getId(), request(List.of(newCategory)));
            preferenceRepository.flush();
            throw new IllegalStateException("Abort replacement");
        }));

        assertEquals(List.of(oldCategory), preferenceRepository.findCategoryIdsByUserId(user.getId()));
    }

    @Test
    void replacementDoesNotChangeAnotherUsersPreferences() {
        UserEntity user = createUser();
        UserEntity otherUser = createUser();
        UUID oldCategory = UUID.randomUUID();
        UUID newCategory = UUID.randomUUID();
        savePreference(user, oldCategory);
        savePreference(otherUser, oldCategory);

        service.updateCategoryPreferences(user.getId(), request(List.of(newCategory)));

        assertEquals(List.of(newCategory), preferenceRepository.findCategoryIdsByUserId(user.getId()));
        assertEquals(List.of(oldCategory), preferenceRepository.findCategoryIdsByUserId(otherUser.getId()));
    }

    private UserEntity createUser() {
        return userRepository.saveAndFlush(UserEntity.builder().username(UUID.randomUUID().toString())
                .email(UUID.randomUUID() + "@example.com").passwordHash("test-hash").build());
    }

    private void savePreference(UserEntity user, UUID categoryId) {
        preferenceRepository.saveAndFlush(UserCategoryPreferenceEntity.builder()
                .id(UserCategoryPreferenceId.builder().userId(user.getId()).categoryId(categoryId).build())
                .user(user).build());
    }

    private UpdateCategoryPreferencesRequest request(List<UUID> categoryIds) {
        return UpdateCategoryPreferencesRequest.builder().categoryIds(categoryIds).build();
    }
}
