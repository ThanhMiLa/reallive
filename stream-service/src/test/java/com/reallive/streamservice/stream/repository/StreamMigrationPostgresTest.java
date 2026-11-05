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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        assertEquals(4, flyway.migrate().migrationsExecuted);
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

        assertEquals(3, migration(null).migrate().migrationsExecuted);
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

        assertEquals(2, migration(null).migrate().migrationsExecuted);
        assertNoSessionVersionColumn();
        assertEquals("READY", jdbc.queryForObject("select status from " + table("stream_sessions") + " where id=?", String.class, session));
        assertEquals("Preserved across version removal", jdbc.queryForObject("select reason from " + table("stream_status_history") + " where stream_id=?", String.class, session));
    }

    @Test
    void upgradeFromV3PreservesExistingSessionsAndLeavesThumbnailStateNull() {
        migration("3").migrate();
        UUID ready = insertSession(UUID.randomUUID(), "READY", "OPEN");
        UUID live = insertSession(UUID.randomUUID(), "READY", "OPEN");
        LocalDateTime futureStartedAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0);
        jdbc.update("update " + table("stream_sessions")
                        + " set status='LIVE',started_at=?,media_source_id=?,media_source_type='rtmpConn' where id=?",
                Timestamp.valueOf(futureStartedAt), UUID.randomUUID(), live);
        jdbc.update("insert into " + table("stream_status_history")
                + " (stream_id,status,reason) values (?,'LIVE','Preserved before thumbnail migration')", live);

        assertEquals(1, migration(null).migrate().migrationsExecuted);

        assertEquals("LIVE", jdbc.queryForObject("select status from " + table("stream_sessions") + " where id=?", String.class, live));
        assertEquals(Timestamp.valueOf(futureStartedAt), jdbc.queryForObject(
                "select started_at from " + table("stream_sessions") + " where id=?", Timestamp.class, live));
        assertEquals("Preserved before thumbnail migration", jdbc.queryForObject(
                "select reason from " + table("stream_status_history") + " where stream_id=?", String.class, live));
        assertThumbnailStateNull(ready);
        assertThumbnailStateNull(live);
        assertEquals(1, jdbc.queryForObject("""
                        SELECT count(*)
                        FROM %s
                        WHERE id = ?
                          AND status = 'LIVE'
                          AND (thumbnail_job_status IS NULL
                               OR thumbnail_job_status NOT IN ('PENDING_DISPATCH', 'ACCEPTED'))
                          AND (thumbnail_updated_at IS NULL OR thumbnail_updated_at <= ?)
                        """.formatted(table("stream_sessions")),
                Integer.class, live, Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC))));
    }

    @Test
    void addsNullableThumbnailColumnsAndSchedulingIndexes() {
        migration(null).migrate();

        assertColumn("thumbnail_url", "character varying", 1000);
        assertColumn("thumbnail_object_key", "character varying", 500);
        assertColumn("thumbnail_updated_at", "timestamp without time zone", null);
        assertColumn("thumbnail_job_id", "uuid", null);
        assertColumn("thumbnail_job_status", "character varying", 30);
        assertColumn("thumbnail_job_deadline_at", "timestamp without time zone", null);

        List<String> indexes = jdbc.queryForList(
                "select indexdef from pg_indexes where schemaname=? and tablename='stream_sessions'",
                String.class, schema);
        assertTrue(indexes.stream().anyMatch(index -> index.contains("idx_stream_sessions_thumbnail_due")
                && index.contains("thumbnail_updated_at")
                && index.contains("NULLS FIRST")
                && index.contains("'LIVE'")
                && index.contains("PENDING_DISPATCH")
                && index.contains("ACCEPTED")));
        assertTrue(indexes.stream().anyMatch(index -> index.contains("idx_stream_sessions_thumbnail_pending_dispatch")
                && index.contains("thumbnail_job_deadline_at")
                && index.contains("PENDING_DISPATCH")));
        assertTrue(indexes.stream().anyMatch(index -> index.contains("idx_stream_sessions_thumbnail_job_deadline")
                && index.contains("thumbnail_job_deadline_at")
                && index.contains("PENDING_DISPATCH")
                && index.contains("ACCEPTED")));
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
    @ValueSource(strings = {"PENDING_DISPATCH", "ACCEPTED", "SUCCEEDED", "FAILED"})
    void acceptsEveryCompleteThumbnailJobState(String status) {
        migration(null).migrate();
        UUID session = insertSession(UUID.randomUUID(), "READY", "OPEN");
        UUID jobId = UUID.randomUUID();
        LocalDateTime deadline = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(4).withNano(0);

        jdbc.update("update " + table("stream_sessions")
                        + " set thumbnail_job_id=?,thumbnail_job_status=?,thumbnail_job_deadline_at=? where id=?",
                jobId, status, Timestamp.valueOf(deadline), session);

        assertEquals(jobId, jdbc.queryForObject(
                "select thumbnail_job_id from " + table("stream_sessions") + " where id=?", UUID.class, session));
        assertEquals(status, jdbc.queryForObject(
                "select thumbnail_job_status from " + table("stream_sessions") + " where id=?", String.class, session));
        assertEquals(Timestamp.valueOf(deadline), jdbc.queryForObject(
                "select thumbnail_job_deadline_at from " + table("stream_sessions") + " where id=?", Timestamp.class, session));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "title='   '", "visibility='SECRET'", "status='CREATED'", "media_path_state='PENDING_OPEN'",
            "media_path_state='PENDING_CLOSE'", "media_path_state='CLOSED'", "media_source_type='rtspSession'",
            "status='RECONNECTING'", "status='LIVE'", "status='ENDED'", "ended_at=current_timestamp",
            "thumbnail_job_status='QUEUED'", "thumbnail_job_status='PENDING_DISPATCH'",
            "thumbnail_job_id=gen_random_uuid()", "thumbnail_job_deadline_at=current_timestamp",
            "thumbnail_job_status='SUCCEEDED',thumbnail_job_id=gen_random_uuid()"
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

    private void assertColumn(String name, String dataType, Integer maximumLength) {
        Map<String, Object> column = jdbc.queryForMap(
                "select data_type,character_maximum_length,is_nullable from information_schema.columns"
                        + " where table_schema=? and table_name='stream_sessions' and column_name=?",
                schema, name);
        assertEquals(dataType, column.get("data_type"));
        assertEquals(maximumLength, column.get("character_maximum_length"));
        assertEquals("YES", column.get("is_nullable"));
    }

    private void assertThumbnailStateNull(UUID sessionId) {
        Map<String, Object> thumbnail = jdbc.queryForMap("""
                        SELECT thumbnail_url,
                               thumbnail_object_key,
                               thumbnail_updated_at,
                               thumbnail_job_id,
                               thumbnail_job_status,
                               thumbnail_job_deadline_at
                        FROM %s
                        WHERE id = ?
                        """.formatted(table("stream_sessions")), sessionId);
        assertEquals(6, thumbnail.size());
        assertTrue(thumbnail.values().stream().allMatch(Objects::isNull));
    }

    private void assertNoSessionVersionColumn() {
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema=? and table_name='stream_sessions' and column_name='version'",
                Integer.class, schema));
    }
}
