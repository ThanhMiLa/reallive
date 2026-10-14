package com.reallive.userservice.user.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserCategoryPreferenceMigrationTest {

    private final String schema = "pref_migration_" + UUID.randomUUID().toString().replace("-", "");
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
    void migratesToV7AndEnforcesCascadeDelete() {
        Flyway flyway = migrations().load();
        flyway.migrate();
        flyway.validate();

        UUID userId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        jdbc.update("INSERT INTO " + table("users") + " (id, username, email, password_hash) VALUES (?, ?, ?, ?)",
                userId, "prefuser", "pref@example.com", "hash");
        jdbc.update("INSERT INTO " + table("user_category_preferences") + " (user_id, category_id) VALUES (?, ?)",
                userId, categoryId);

        assertEquals(1, count("user_category_preferences", "WHERE user_id = '" + userId + "'"));

        // Cascade delete on user deletion
        jdbc.update("DELETE FROM " + table("users") + " WHERE id = ?", userId);
        assertEquals(0, count("user_category_preferences", "WHERE user_id = '" + userId + "'"));
    }

    private FluentConfiguration migrations() {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema);
    }

    private HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:pref_migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
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
