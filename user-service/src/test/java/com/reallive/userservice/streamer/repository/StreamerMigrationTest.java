package com.reallive.userservice.streamer.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StreamerMigrationTest {

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
    void migratesFreshDatabaseAndSeedsStreamerRoleWithoutGrantingPrivileges() {
        Flyway flyway = migrations().load();
        assertEquals(6, flyway.migrate().migrationsExecuted);
        flyway.validate();

        assertEquals(1, count("roles", "WHERE name = 'STREAMER'"));
        assertEquals(0, count("user_roles", ""));
        assertEquals(0, count("role_permissions", ""));
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
        assertEquals(3, flyway.migrate().migrationsExecuted);
        flyway.validate();

        assertEquals(1, count("roles", "WHERE name = 'STREAMER'"));
        assertEquals(roleId, jdbc.queryForObject("SELECT id FROM " + table("roles") + " WHERE name = 'STREAMER'", UUID.class));
        assertEquals(1, count("role_permissions", ""));
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
        assertEquals(2, flyway.migrate().migrationsExecuted);
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
