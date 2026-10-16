package com.reallive.channelservice.channel.repository;

import com.reallive.channelservice.channel.entity.CategoryEntity;
import com.reallive.channelservice.channel.entity.enums.CategoryStatus;
import com.reallive.channelservice.channel.entity.ChannelCategoryEntity;
import com.reallive.channelservice.channel.entity.ChannelCategoryId;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.ChannelFollowerEntity;
import com.reallive.channelservice.channel.entity.ChannelFollowerId;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(showSql = false)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChannelPersistenceTest {

    private static final String SCHEMA = "channel_persistence_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private ChannelRepository channels;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private ChannelCategoryRepository channelCategories;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManager entityManager;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("channel.test.database-url",
                "jdbc:h2:mem:channel_persistence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
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

    @Test
    void migrationCreatesAllTablesAndDoesNotReapply() {
        flyway.validate();
        assertEquals("3", flyway.info().current().getVersion().toString());
        assertEquals(0, flyway.migrate().migrationsExecuted);
        for (String name : List.of("channels", "categories", "channel_categories", "channel_followers")) {
            assertNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM " + table(name), Integer.class));
        }
    }

    @Test
    void followerMigrationUpgradesExistingChannelsWithZeroCount() {
        String schema = "channel_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway initial = Flyway.configure().dataSource(jdbc.getDataSource())
                .schemas(schema).defaultSchema(schema).target("1").load();
        try {
            initial.migrate();
            UUID channelId = UUID.randomUUID();
            String channelsTable = "\"" + schema + "\".channels";
            jdbc.update("INSERT INTO " + channelsTable
                    + " (id, streamer_id, name, slug, status) VALUES (?, ?, ?, ?, ?)",
                    channelId, UUID.randomUUID(), "Existing channel", "existing-channel", "ACTIVE");

            Flyway upgraded = Flyway.configure().dataSource(jdbc.getDataSource())
                    .schemas(schema).defaultSchema(schema).load();
            assertEquals(2, upgraded.migrate().migrationsExecuted);
            upgraded.validate();
            assertEquals(0L, jdbc.queryForObject("SELECT follower_count FROM " + channelsTable
                    + " WHERE id = ?", Long.class, channelId));
            assertEquals("Existing channel", jdbc.queryForObject("SELECT name FROM " + channelsTable
                    + " WHERE id = ?", String.class, channelId));
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }

    @Test
    void userIdMigrationPreservesExistingChannelsAndLeavesUnknownOwnersNull() {
        String schema = "channel_owner_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway initial = Flyway.configure().dataSource(jdbc.getDataSource())
                .schemas(schema).defaultSchema(schema).target("2").load();
        try {
            initial.migrate();
            UUID channelId = UUID.randomUUID();
            UUID streamerId = UUID.randomUUID();
            String channelsTable = "\"" + schema + "\".channels";
            jdbc.update("INSERT INTO " + channelsTable
                    + " (id, streamer_id, name, slug, status, follower_count) VALUES (?, ?, ?, ?, ?, ?)",
                    channelId, streamerId, "Existing channel", "existing-channel", "BANNED", 123L);
            Map<String, Object> before = jdbc.queryForMap("SELECT * FROM " + channelsTable + " WHERE id = ?", channelId);

            Flyway upgraded = Flyway.configure().dataSource(jdbc.getDataSource())
                    .schemas(schema).defaultSchema(schema).load();
            assertEquals(1, upgraded.migrate().migrationsExecuted);
            upgraded.validate();
            Map<String, Object> after = jdbc.queryForMap("SELECT * FROM " + channelsTable + " WHERE id = ?", channelId);
            for (String column : before.keySet()) {
                assertEquals(before.get(column), after.get(column));
            }
            assertNull(jdbc.queryForObject("SELECT user_id FROM " + channelsTable + " WHERE id = ?",
                    UUID.class, channelId));
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }

    @Test
    void persistsChannelProfileWithExternalStreamerIdAndTimestamps() {
        ChannelEntity channel = newChannel();
        channel.setUserId(UUID.randomUUID());
        channel.setDescription("Channel description");
        channel.setAvatarUrl("https://example.com/avatar.png");
        channel.setBannerUrl("https://example.com/banner.png");
        ChannelEntity saved = channels.saveAndFlush(channel);
        ChannelEntity reloaded = channels.findByStreamerId(channel.getStreamerId()).orElseThrow();

        assertNotNull(saved.getId());
        assertEquals(saved.getId(), reloaded.getId());
        assertEquals(channel.getUserId(), reloaded.getUserId());
        assertEquals(channel.getName(), reloaded.getName());
        assertEquals(channel.getDescription(), reloaded.getDescription());
        assertEquals(channel.getAvatarUrl(), reloaded.getAvatarUrl());
        assertEquals(channel.getBannerUrl(), reloaded.getBannerUrl());
        assertEquals(ChannelStatus.ACTIVE, reloaded.getStatus());
        assertEquals(0L, reloaded.getFollowerCount());
        assertNotNull(reloaded.getCreatedAt());
        assertNotNull(reloaded.getUpdatedAt());
        assertEquals(saved.getId(), channels.findBySlug(saved.getSlug()).orElseThrow().getId());
        assertTrue(channels.existsByStreamerId(saved.getStreamerId()));
        assertTrue(channels.existsBySlug(saved.getSlug()));
    }

    @Test
    void updatesChannelAndSoftDeleteTimestampWithoutChangingIdentity() {
        ChannelEntity saved = channels.saveAndFlush(newChannel());
        long visibleCount = channels.count();
        LocalDateTime createdAt = saved.getCreatedAt();
        LocalDateTime deletedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        saved.setName("Updated channel");
        saved.setStatus(ChannelStatus.BANNED);
        saved.setDeletedAt(deletedAt);
        channels.saveAndFlush(saved);

        assertTrue(channels.findById(saved.getId()).isEmpty());
        assertTrue(channels.findByStreamerId(saved.getStreamerId()).isEmpty());
        assertTrue(channels.findBySlug(saved.getSlug()).isEmpty());
        assertFalse(channels.existsById(saved.getId()));
        assertFalse(channels.existsByStreamerId(saved.getStreamerId()));
        assertFalse(channels.existsBySlug(saved.getSlug()));
        assertTrue(channels.existsByStreamerIdIncludingDeleted(saved.getStreamerId()));
        assertEquals(visibleCount - 1, channels.count());
        assertFalse(channels.findAll().stream().anyMatch(channel -> channel.getId().equals(saved.getId())));

        Map<String, Object> stored = jdbc.queryForMap("SELECT * FROM " + table("channels") + " WHERE id = ?", saved.getId());
        assertEquals(saved.getStreamerId(), stored.get("streamer_id"));
        assertEquals("Updated channel", stored.get("name"));
        assertEquals("BANNED", stored.get("status"));
        assertEquals(deletedAt, ((Timestamp) stored.get("deleted_at")).toLocalDateTime());
        assertEquals(createdAt, ((Timestamp) stored.get("created_at")).toLocalDateTime());
        assertFalse(((Timestamp) stored.get("updated_at")).toLocalDateTime().isBefore(createdAt));
    }

    @Test
    void persistsAndUpdatesCategoryWithUniqueNameAndSlugLookups() {
        CategoryEntity saved = categories.saveAndFlush(newCategory());
        LocalDateTime createdAt = saved.getCreatedAt();
        assertNotNull(saved.getId());
        assertNotNull(createdAt);
        assertNotNull(saved.getUpdatedAt());
        assertEquals(saved.getId(), categories.findByName(saved.getName()).orElseThrow().getId());
        assertTrue(categories.existsByName(saved.getName()));
        assertTrue(categories.existsBySlug(saved.getSlug()));

        saved.setStatus(CategoryStatus.INACTIVE);
        saved.setThumbnailUrl("https://example.com/category.png");
        categories.saveAndFlush(saved);

        CategoryEntity reloaded = categories.findBySlug(saved.getSlug()).orElseThrow();
        assertEquals(CategoryStatus.INACTIVE, reloaded.getStatus());
        assertEquals(saved.getThumbnailUrl(), reloaded.getThumbnailUrl());
        assertEquals(createdAt, reloaded.getCreatedAt());
        assertFalse(reloaded.getUpdatedAt().isBefore(createdAt));
    }

    @Test
    void persistsCompositeKeyAndQueriesBothSidesOfChannelCategoryRelationship() {
        ChannelEntity firstChannel = channels.saveAndFlush(newChannel());
        ChannelEntity secondChannel = channels.saveAndFlush(newChannel());
        CategoryEntity firstCategory = categories.saveAndFlush(newCategory());
        CategoryEntity secondCategory = categories.saveAndFlush(newCategory());
        ChannelCategoryEntity firstLink = saveLink(firstChannel, firstCategory);
        saveLink(firstChannel, secondCategory);
        saveLink(secondChannel, firstCategory);

        ChannelCategoryEntity reloaded = channelCategories.findById(firstLink.getId()).orElseThrow();
        assertEquals(firstChannel.getId(), reloaded.getChannel().getId());
        assertEquals(firstCategory.getId(), reloaded.getCategory().getId());
        assertNotNull(reloaded.getCreatedAt());
        assertEquals(2, channelCategories.findAllById_ChannelId(firstChannel.getId()).size());
        assertEquals(2, channelCategories.findAllById_CategoryId(firstCategory.getId()).size());

        channelCategories.deleteById(firstLink.getId());
        assertFalse(channelCategories.existsById(firstLink.getId()));
        assertTrue(channels.existsById(firstChannel.getId()));
        assertTrue(categories.existsById(firstCategory.getId()));
    }

    @Test
    void databaseGeneratesIdsAndAuditTimestampsForNativeInserts() {
        UUID streamerId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO " + table("channels")
                + " (streamer_id, name, slug, status) VALUES (?, ?, ?, ?)", streamerId, suffix, suffix, "ACTIVE");
        jdbc.update("INSERT INTO " + table("categories")
                + " (name, slug, status) VALUES (?, ?, ?)", suffix, suffix, "ACTIVE");
        ChannelEntity channel = channels.findByStreamerId(streamerId).orElseThrow();
        CategoryEntity category = categories.findBySlug(suffix).orElseThrow();
        insertLink(channel.getId(), category.getId());

        assertNotNull(channel.getId());
        assertNotNull(channel.getCreatedAt());
        assertNotNull(channel.getUpdatedAt());
        assertEquals(0L, channel.getFollowerCount());
        assertNotNull(category.getId());
        assertNotNull(category.getCreatedAt());
        assertNotNull(category.getUpdatedAt());
        assertNotNull(channelCategories.findById(new ChannelCategoryId(channel.getId(), category.getId()))
                .orElseThrow().getCreatedAt());
    }

    @Test
    void persistsFollowerCountsBeyondIntegerRangeAndRejectsInvalidCounts() {
        ChannelEntity channel = newChannel();
        channel.setFollowerCount(3_000_000_000L);
        channels.saveAndFlush(channel);

        assertEquals(3_000_000_000L, channels.findById(channel.getId()).orElseThrow().getFollowerCount());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE " + table("channels")
                + " SET follower_count = -1 WHERE id = ?", channel.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE " + table("channels")
                + " SET follower_count = NULL WHERE id = ?", channel.getId()));
    }

    @Test
    @Transactional
    void persistsFollowerWithCompositeKeyExternalUserAndFollowTimestamp() {
        ChannelEntity channel = channels.saveAndFlush(newChannel());
        UUID userId = UUID.randomUUID();
        ChannelFollowerId id = new ChannelFollowerId(channel.getId(), userId);
        ChannelFollowerEntity follower = ChannelFollowerEntity.builder().id(id).channel(channel).build();
        entityManager.persist(follower);
        entityManager.flush();
        entityManager.clear();

        ChannelFollowerEntity reloaded = entityManager.find(ChannelFollowerEntity.class, id);
        assertNotNull(reloaded);
        assertEquals(userId, reloaded.getId().getUserId());
        assertEquals(channel.getId(), reloaded.getChannel().getId());
        assertNotNull(reloaded.getFollowedAt());

        entityManager.remove(reloaded);
        entityManager.flush();
        entityManager.clear();
        assertTrue(channels.existsById(channel.getId()));
    }

    @Test
    void followerConstraintsPreventDuplicateFollowsAndMissingChannels() {
        ChannelEntity firstChannel = channels.saveAndFlush(newChannel());
        ChannelEntity secondChannel = channels.saveAndFlush(newChannel());
        UUID userId = UUID.randomUUID();
        insertFollower(firstChannel.getId(), userId);
        insertFollower(firstChannel.getId(), UUID.randomUUID());
        insertFollower(secondChannel.getId(), userId);

        assertNotNull(jdbc.queryForObject("SELECT followed_at FROM " + table("channel_followers")
                + " WHERE channel_id = ? AND user_id = ?", Timestamp.class, firstChannel.getId(), userId));
        assertThrows(DataIntegrityViolationException.class, () -> insertFollower(firstChannel.getId(), userId));
        assertThrows(DataIntegrityViolationException.class, () -> insertFollower(UUID.randomUUID(), userId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM " + table("channels")
                + " WHERE id = ?", firstChannel.getId()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void databaseRejectsNullRequiredFollowerColumns(int columnIndex) {
        ChannelEntity channel = channels.saveAndFlush(newChannel());
        Object[] columns = {channel.getId(), UUID.randomUUID(), LocalDateTime.now()};
        columns[columnIndex] = null;

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO " + table("channel_followers")
                + " (channel_id, user_id, followed_at) VALUES (?, ?, ?)", columns));
    }

    @ParameterizedTest
    @ValueSource(strings = {"streamer_id", "slug"})
    void databaseRejectsDuplicateChannelOwnerOrSlug(String column) {
        ChannelEntity existing = channels.saveAndFlush(newChannel());
        UUID streamerId = column.equals("streamer_id") ? existing.getStreamerId() : UUID.randomUUID();
        String slug = column.equals("slug") ? existing.getSlug() : UUID.randomUUID().toString();

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO " + table("channels")
                + " (streamer_id, name, slug, status) VALUES (?, ?, ?, ?)", streamerId, "Duplicate", slug, "ACTIVE"));
        assertEquals(existing.getId(), channels.findByStreamerId(existing.getStreamerId()).orElseThrow().getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "slug"})
    void databaseRejectsDuplicateCategoryNameOrSlug(String column) {
        CategoryEntity existing = categories.saveAndFlush(newCategory());
        String name = column.equals("name") ? existing.getName() : UUID.randomUUID().toString();
        String slug = column.equals("slug") ? existing.getSlug() : UUID.randomUUID().toString();

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO " + table("categories")
                + " (name, slug, status) VALUES (?, ?, ?)", name, slug, "ACTIVE"));
    }

    @ParameterizedTest
    @CsvSource({"channels, INACTIVE", "channels, UNKNOWN", "categories, BANNED", "categories, UNKNOWN"})
    void databaseRejectsStatusesOutsideEachDomain(String name, String status) {
        if (name.equals("channels")) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO " + table(name)
                    + " (streamer_id, name, slug, status) VALUES (?, ?, ?, ?)", UUID.randomUUID(), "Invalid",
                    UUID.randomUUID().toString(), status));
        } else {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO " + table(name)
                    + " (name, slug, status) VALUES (?, ?, ?)", UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), status));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void databaseRejectsNullRequiredChannelColumns(int columnIndex) {
        Object[] columns = {UUID.randomUUID(), UUID.randomUUID(), "Channel", UUID.randomUUID().toString(),
            "ACTIVE", LocalDateTime.now(), LocalDateTime.now()};
        columns[columnIndex] = null;

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO " + table("channels")
                + " (id, streamer_id, name, slug, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)", columns));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void databaseRejectsNullRequiredCategoryColumns(int columnIndex) {
        Object[] columns = {UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "ACTIVE", LocalDateTime.now(), LocalDateTime.now()};
        columns[columnIndex] = null;

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO " + table("categories")
                + " (id, name, slug, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)", columns));
    }

    @Test
    void databaseRejectsDuplicateLinkAndMissingParents() {
        ChannelEntity channel = channels.saveAndFlush(newChannel());
        CategoryEntity category = categories.saveAndFlush(newCategory());
        insertLink(channel.getId(), category.getId());

        assertThrows(DataIntegrityViolationException.class, () -> insertLink(channel.getId(), category.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> insertLink(UUID.randomUUID(), category.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> insertLink(channel.getId(), UUID.randomUUID()));
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("DELETE FROM " + table("channels") + " WHERE id = ?", channel.getId()));
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("DELETE FROM " + table("categories") + " WHERE id = ?", category.getId()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void databaseRejectsNullRequiredLinkColumns(int columnIndex) {
        ChannelEntity channel = channels.saveAndFlush(newChannel());
        CategoryEntity category = categories.saveAndFlush(newCategory());
        Object[] columns = {channel.getId(), category.getId(), LocalDateTime.now()};
        columns[columnIndex] = null;

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO " + table("channel_categories")
                + " (channel_id, category_id, created_at) VALUES (?, ?, ?)", columns));
    }

    @Test
    void missingIdentifiersReturnEmptyResults() {
        UUID missingId = UUID.randomUUID();
        String missingValue = missingId.toString();
        assertTrue(channels.findByStreamerId(missingId).isEmpty());
        assertTrue(channels.findBySlug(missingValue).isEmpty());
        assertFalse(channels.existsByStreamerId(missingId));
        assertFalse(channels.existsBySlug(missingValue));
        assertTrue(categories.findByName(missingValue).isEmpty());
        assertTrue(categories.findBySlug(missingValue).isEmpty());
        assertFalse(categories.existsByName(missingValue));
        assertFalse(categories.existsBySlug(missingValue));
        assertTrue(channelCategories.findAllById_ChannelId(missingId).isEmpty());
        assertTrue(channelCategories.findAllById_CategoryId(missingId).isEmpty());
    }

    private ChannelEntity newChannel() {
        return ChannelEntity.builder().streamerId(UUID.randomUUID()).name("Channel")
                .slug(UUID.randomUUID().toString()).status(ChannelStatus.ACTIVE).build();
    }

    private CategoryEntity newCategory() {
        String suffix = UUID.randomUUID().toString();
        return CategoryEntity.builder().name("Category " + suffix).slug(suffix).status(CategoryStatus.ACTIVE).build();
    }

    private ChannelCategoryEntity saveLink(ChannelEntity channel, CategoryEntity category) {
        return channelCategories.saveAndFlush(ChannelCategoryEntity.builder()
                .id(new ChannelCategoryId(channel.getId(), category.getId()))
                .channel(channel).category(category).build());
    }

    private void insertLink(UUID channelId, UUID categoryId) {
        jdbc.update("INSERT INTO " + table("channel_categories") + " (channel_id, category_id) VALUES (?, ?)",
                channelId, categoryId);
    }

    private void insertFollower(UUID channelId, UUID userId) {
        jdbc.update("INSERT INTO " + table("channel_followers") + " (channel_id, user_id) VALUES (?, ?)",
                channelId, userId);
    }

    private String table(String name) {
        return "\"" + SCHEMA + "\"." + name;
    }
}
