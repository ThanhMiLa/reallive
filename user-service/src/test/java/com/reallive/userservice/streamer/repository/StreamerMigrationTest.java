package com.reallive.userservice.streamer.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StreamerMigrationTest {

    private static final List<String> STREAM_PERMISSIONS = List.of(
            "STREAM_CREATE", "STREAM_END", "STREAM_PUBLISH", "STREAM_UPDATE", "STREAM_VIEW_OWN");

    private final String schema = "streamer_migration_" + UUID.randomUUID().toString().replace("-", "");
    private final HikariDataSource dataSource = createDataSource();
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    @AfterEach
    void dropTestSchema() {
        try {
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        } finally {
            dataSource.close();
        }
    }

    @Test
    void migratesFreshDatabaseAndGrantsChannelAndStreamPermissionsToStreamerRole() {
        Flyway flyway = migrations().load();
        assertEquals(10, flyway.migrate().migrationsExecuted);
        flyway.validate();

        assertEquals(1, count("roles", "WHERE name = 'STREAMER'"));
        assertEquals(0, count("user_roles", ""));
        assertEquals(1, count("permissions", "WHERE name = 'CHANNEL_VIEW_OWN'"));
        assertEquals(STREAM_PERMISSIONS, streamerPermissions());
        assertEquals(6, count("role_permissions", ""));
        assertEquals(0, count("auth_state_sync_tasks", ""));
        assertEquals(0, count("streamers", ""));
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void upgradesV3AndPreservesExistingRolePermissionsAndUsers() {
        migrations().target("3").load().migrate();
        UUID roleId = UUID.randomUUID();
        UUID permissionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("roles") + " (id, name) VALUES (?, 'STREAMER')", roleId);
        jdbc.update("INSERT INTO " + table("permissions") + " (id, name) VALUES (?, 'EXISTING_PERMISSION')", permissionId);
        jdbc.update("INSERT INTO " + table("role_permissions") + " (role_id, permission_id) VALUES (?, ?)",
                roleId, permissionId);
        jdbc.update("INSERT INTO " + table("users") + " (id, username, email, password_hash) VALUES (?, ?, ?, ?)",
                userId, "existing-user", "existing@example.com", "test-hash");
        jdbc.update("INSERT INTO " + table("user_roles") + " (user_id, role_id) VALUES (?, ?)", userId, roleId);

        Flyway flyway = migrations().load();
        assertEquals(7, flyway.migrate().migrationsExecuted);
        flyway.validate();

        assertEquals(1, count("roles", "WHERE name = 'STREAMER'"));
        assertEquals(roleId, jdbc.queryForObject("SELECT id FROM " + table("roles") + " WHERE name = 'STREAMER'", UUID.class));
        assertEquals(7, count("role_permissions", ""));
        assertEquals(1, count("role_permissions", "WHERE permission_id = '" + permissionId + "'"));
        assertEquals(1, count("role_permissions", "WHERE permission_id = (SELECT id FROM "
                + table("permissions") + " WHERE name = 'CHANNEL_VIEW_OWN')"));
        assertEquals(1, count("user_roles", ""));
        assertEquals(1, count("users", ""));
        jdbc.update("INSERT INTO " + table("streamers") + " (user_id, status) VALUES (?, 'ACTIVE')", userId);
        assertNotNull(jdbc.queryForObject("SELECT id FROM " + table("streamers"), UUID.class));
    }

    @Test
    void upgradesHistoricalProfilesV4WithoutReusingItsVersionOrLosingData() {
        migrations().target("4").load().migrate();
        assertEquals(-843705742, jdbc.queryForObject("SELECT \"checksum\" FROM "
                + table("\"flyway_schema_history\"") + " WHERE \"version\" = '4'", Integer.class));
        assertEquals("V4__create_profiles_table.sql", jdbc.queryForObject("SELECT \"script\" FROM "
                + table("\"flyway_schema_history\"") + " WHERE \"version\" = '4'", String.class));
        UUID userId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("users") + " (id, username, email, password_hash) VALUES (?, ?, ?, ?)",
                userId, "legacy-profile-user", "legacy@example.com", "test-hash");
        jdbc.update("INSERT INTO " + table("profiles") + " (id, user_id, display_name) VALUES (?, ?, NULL)",
                profileId, userId);

        Flyway flyway = migrations().load();
        assertEquals(6, flyway.migrate().migrationsExecuted);
        flyway.validate();

        assertEquals(1, count("users", ""));
        assertEquals(1, count("profiles", ""));
        assertNull(jdbc.queryForObject("SELECT display_name FROM " + table("profiles") + " WHERE id = ?",
                String.class, profileId));
        assertEquals(0, count("streamers", ""));
        assertEquals(1, count("roles", "WHERE name = 'STREAMER'"));
        assertEquals(-843705742, jdbc.queryForObject("SELECT \"checksum\" FROM "
                + table("\"flyway_schema_history\"") + " WHERE \"version\" = '4'", Integer.class));
        assertEquals("V6__create_streamers_table.sql", jdbc.queryForObject("SELECT \"script\" FROM "
                + table("\"flyway_schema_history\"") + " WHERE \"version\" = '6'", String.class));
    }

    @Test
    void upgradesV9WithoutDuplicateGrantsAndQueuesOnlyExistingStreamersForAuthStateSync() {
        migrations().target("9").load().migrate();
        UUID streamerRoleId = jdbc.queryForObject("SELECT id FROM " + table("roles")
                + " WHERE name = 'STREAMER'", UUID.class);
        UUID viewerRoleId = UUID.randomUUID();
        UUID adminRoleId = UUID.randomUUID();
        UUID existingPermissionId = UUID.randomUUID();
        UUID streamerWithPendingTask = UUID.randomUUID();
        UUID streamerWithoutPendingTask = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("roles") + " (id, name) VALUES (?, 'USER'), (?, 'ADMIN')",
                viewerRoleId, adminRoleId);
        jdbc.update("INSERT INTO " + table("permissions") + " (id, name) VALUES (?, 'STREAM_CREATE')",
                existingPermissionId);
        jdbc.update("INSERT INTO " + table("role_permissions") + " (role_id, permission_id) VALUES (?, ?)",
                streamerRoleId, existingPermissionId);
        jdbc.update("INSERT INTO " + table("users")
                        + " (id, username, email, password_hash, auth_revision) VALUES"
                        + " (?, 'streamer-one', 'one@example.com', 'hash', 7),"
                        + " (?, 'streamer-two', 'two@example.com', 'hash', 0),"
                        + " (?, 'viewer', 'viewer@example.com', 'hash', 5)",
                streamerWithPendingTask, streamerWithoutPendingTask, viewer);
        jdbc.update("INSERT INTO " + table("user_roles") + " (user_id, role_id) VALUES (?, ?), (?, ?), (?, ?), (?, ?)",
                streamerWithPendingTask, streamerRoleId, streamerWithPendingTask, viewerRoleId,
                streamerWithoutPendingTask, streamerRoleId, viewer, viewerRoleId);
        LocalDateTime nextRetry = LocalDateTime.now().plusDays(1).withNano(0);
        jdbc.update("INSERT INTO " + table("auth_state_sync_tasks")
                        + " (user_id, revision, attempts, next_attempt_at) VALUES (?, 7, 4, ?), (?, 5, 3, ?)",
                streamerWithPendingTask, nextRetry, viewer, nextRetry);

        Flyway flyway = migrations().load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        flyway.validate();

        assertEquals(STREAM_PERMISSIONS, streamerPermissions());
        assertEquals(existingPermissionId, jdbc.queryForObject("SELECT id FROM " + table("permissions")
                + " WHERE name = 'STREAM_CREATE'", UUID.class));
        assertEquals(6, count("permissions", ""));
        assertEquals(6, count("role_permissions", ""));
        assertEquals(0, count("role_permissions", "WHERE role_id IN ('" + viewerRoleId + "', '" + adminRoleId + "')"));
        assertEquals(4, count("user_roles", ""));
        assertEquals(8L, jdbc.queryForObject("SELECT auth_revision FROM " + table("users") + " WHERE id = ?",
                Long.class, streamerWithPendingTask));
        assertEquals(1L, jdbc.queryForObject("SELECT auth_revision FROM " + table("users") + " WHERE id = ?",
                Long.class, streamerWithoutPendingTask));
        assertEquals(5L, jdbc.queryForObject("SELECT auth_revision FROM " + table("users") + " WHERE id = ?",
                Long.class, viewer));
        assertEquals(3, count("auth_state_sync_tasks", ""));
        assertEquals(8L, jdbc.queryForObject("SELECT revision FROM " + table("auth_state_sync_tasks")
                + " WHERE user_id = ?", Long.class, streamerWithPendingTask));
        assertEquals(1L, jdbc.queryForObject("SELECT revision FROM " + table("auth_state_sync_tasks")
                + " WHERE user_id = ?", Long.class, streamerWithoutPendingTask));
        assertEquals(2, count("auth_state_sync_tasks", "WHERE attempts = 0 AND next_attempt_at <= CURRENT_TIMESTAMP"));
        assertEquals(3, jdbc.queryForObject("SELECT attempts FROM " + table("auth_state_sync_tasks")
                + " WHERE user_id = ?", Integer.class, viewer));
        assertEquals(nextRetry, jdbc.queryForObject("SELECT next_attempt_at FROM " + table("auth_state_sync_tasks")
                + " WHERE user_id = ?", LocalDateTime.class, viewer));
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(8L, jdbc.queryForObject("SELECT auth_revision FROM " + table("users") + " WHERE id = ?",
                Long.class, streamerWithPendingTask));
    }

    private List<String> streamerPermissions() {
        return jdbc.queryForList("SELECT permission.name FROM " + table("permissions") + " permission"
                + " JOIN " + table("role_permissions") + " role_permission ON role_permission.permission_id = permission.id"
                + " JOIN " + table("roles") + " role ON role.id = role_permission.role_id"
                + " WHERE role.name = 'STREAMER' AND permission.name LIKE 'STREAM_%' ORDER BY permission.name", String.class);
    }

    private FluentConfiguration migrations() {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema);
    }

    private HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getProperty("streamer.test.database-url",
                "jdbc:h2:mem:streamer_migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        config.setUsername(System.getProperty("streamer.test.database-username", "sa"));
        config.setPassword(System.getProperty("streamer.test.database-password", ""));
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }

    private int count(String tableName, String condition) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table(tableName) + " " + condition, Integer.class);
    }

    private String table(String tableName) {
        return "\"" + schema + "\"." + tableName;
    }
}
