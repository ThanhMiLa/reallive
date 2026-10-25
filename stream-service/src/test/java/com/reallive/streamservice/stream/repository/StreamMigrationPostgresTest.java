package com.reallive.streamservice.stream.repository;

import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamMigrationPostgresTest extends PostgresIntegrationTestSupport {

    private final String schema = newSchema();
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = createTestDataSource(2);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() throws SQLException {
        try {
            dropSchema(schema);
        } finally {
            dataSource.close();
        }
    }

    @Test
    void migratesCleanDatabaseToTwoBusinessTablesAndCanRunAgain() {
        Flyway flyway = migration(null);
        assertEquals(3, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(List.of("stream_sessions", "stream_status_history"), jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema=? and table_name <> 'flyway_schema_history' order by table_name",
                String.class, schema));
        assertNoSessionVersionColumn();
    }

    @Test
    void upgradePreservesSessionsHistoryAndRemovesTasks() {
        migration("1").migrate();
        UUID active = insertSession(UUID.randomUUID(), "READY", "OPEN");
        UUID ended = insertSession(UUID.randomUUID(), "ENDED", "CLOSED");
        jdbc.update("insert into " + table("stream_status_history") + " (stream_id,status,reason) values (?,'READY','Existing history')", active);
        jdbc.update("insert into " + table("stream_media_sync_tasks") + " (stream_id,revision) values (?,1)", active);

        assertEquals(2, migration(null).migrate().migrationsExecuted);
        assertEquals(2, jdbc.queryForObject("select count(*) from " + table("stream_sessions"), Integer.class));
        assertEquals("Existing history", jdbc.queryForObject("select reason from " + table("stream_status_history") + " where stream_id=?", String.class, active));
        assertEquals("CLOSED", jdbc.queryForObject("select media_path_state from " + table("stream_sessions") + " where id=?", String.class, ended));
        assertNull(jdbc.queryForObject("select to_regclass(?)::text", String.class, table("stream_media_sync_tasks")));
        assertNoSessionVersionColumn();
    }

    @Test
    void upgradeFromV2RemovesVersionAndPreservesSessionAndHistory() {
        migration("2").migrate();
        UUID session = insertSession(UUID.randomUUID(), "READY", "OPEN");
        jdbc.update("update " + table("stream_sessions") + " set version=7 where id=?", session);
        jdbc.update("insert into " + table("stream_status_history") + " (stream_id,status,reason) values (?,'READY','Preserved across version removal')", session);

        assertEquals(1, migration(null).migrate().migrationsExecuted);
        assertNoSessionVersionColumn();
        assertEquals("READY", jdbc.queryForObject("select status from " + table("stream_sessions") + " where id=?", String.class, session));
        assertEquals("Preserved across version removal", jdbc.queryForObject("select reason from " + table("stream_status_history") + " where stream_id=?", String.class, session));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_OPEN", "PENDING_CLOSE"})
    void upgradeRefusesToAssumePendingMediaIsAlreadySynchronized(String mediaState) {
        migration("1").migrate();
        UUID session = insertSession(UUID.randomUUID(), mediaState.equals("PENDING_OPEN") ? "READY" : "ENDED", mediaState);
        jdbc.update("insert into " + table("stream_media_sync_tasks") + " (stream_id,revision) values (?,1)", session);

        FlywayException error = assertThrows(FlywayException.class, () -> migration(null).migrate());
        assertTrue(error.getMessage().contains("resolve PENDING_OPEN/PENDING_CLOSE"));
        assertEquals(mediaState, jdbc.queryForObject("select media_path_state from " + table("stream_sessions") + " where id=?", String.class, session));
        assertEquals(1, jdbc.queryForObject("select count(*) from " + table("stream_media_sync_tasks"), Integer.class));
        assertEquals("1", jdbc.queryForObject("select version from " + table("flyway_schema_history") + " where version is not null and success order by installed_rank desc limit 1", String.class));
    }

    @Test
    void slotIsHeldUntilEndAndClosedCommitTogether() {
        migration(null).migrate();
        UUID channel = UUID.randomUUID();
        UUID first = insertSession(channel, "READY", "OPEN");
        assertThrows(DataIntegrityViolationException.class, () -> insertSession(channel, "READY", "OPEN"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update " + table("stream_sessions") + " set status='ENDED',ended_at=current_timestamp where id=?", first));
        jdbc.update("update " + table("stream_sessions") + " set status='ENDED',media_path_state='CLOSED',ended_at=current_timestamp where id=?", first);
        assertNotNull(insertSession(channel, "READY", "OPEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "title='   '", "visibility='SECRET'", "status='CREATED'", "media_path_state='PENDING_OPEN'",
            "media_path_state='PENDING_CLOSE'", "media_path_state='CLOSED'", "media_source_type='rtspSession'",
            "status='RECONNECTING'", "status='LIVE'", "status='ENDED'", "ended_at=current_timestamp"
    })
    void constraintsRejectInvalidPersistedStates(String assignment) {
        migration(null).migrate();
        UUID session = insertSession(UUID.randomUUID(), "READY", "OPEN");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update " + table("stream_sessions") + " set " + assignment + " where id=?", session));
    }

    private Flyway migration(String target) {
        var config = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private UUID insertSession(UUID channel, String status, String mediaState) {
        UUID session = UUID.randomUUID();
        jdbc.update("insert into " + table("stream_sessions")
                        + " (id,channel_id,streamer_id,owner_user_id,category_id,title,visibility,status,playback_path,creation_request_id,media_path_state,ended_at)"
                        + " values (?,?,?,?,?,'Migration test','PUBLIC',?,?,?, ?,case when ?='ENDED' then current_timestamp else null end)",
                session, channel, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), status,
                "/live/" + session + "/index.m3u8", UUID.randomUUID(), mediaState, status);
        return session;
    }

    private String table(String name) {
        return "\"" + schema + "\"." + name;
    }

    private void assertNoSessionVersionColumn() {
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema=? and table_name='stream_sessions' and column_name='version'",
                Integer.class, schema));
    }
}
