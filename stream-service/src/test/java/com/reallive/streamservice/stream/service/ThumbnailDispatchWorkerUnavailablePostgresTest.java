package com.reallive.streamservice.stream.service;

import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import com.reallive.streamservice.stream.entity.enums.ThumbnailJobStatus;
import com.reallive.streamservice.stream.repository.StreamSessionRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class ThumbnailDispatchWorkerUnavailablePostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();
    private static final Instant BASE = Instant.parse("2026-11-05T12:00:00Z");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        configureDatabase(registry, SCHEMA);
        registry.add("stream.thumbnail.enabled", () -> "false");
        registry.add("stream.thumbnail.interval", () -> "60s");
        registry.add("stream.thumbnail.batch-size", () -> "50");
        registry.add("stream.thumbnail.job-deadline", () -> "240s");
        registry.add("stream.thumbnail.worker-base-url", () -> "http://127.0.0.1:1");
        registry.add("stream.thumbnail.internal-hls-base-url", () -> "http://media-server:8888");
        registry.add("stream.thumbnail.internal-token", () -> "test-internal-token-0123456789abcdef");
        registry.add("stream.thumbnail.worker-connect-timeout", () -> "300ms");
        registry.add("stream.thumbnail.worker-read-timeout", () -> "500ms");
    }

    @AfterAll
    static void cleanup() throws SQLException {
        dropSchema(SCHEMA);
    }

    @Autowired
    private ThumbnailDispatchService dispatch;

    @Autowired
    private StreamSessionRepository sessions;

    @Test
    void keepsTheCommittedJobPendingWhenTheWorkerCannotBeReached() {
        UUID sessionId = UUID.randomUUID();
        StreamSessionEntity session = sessions.saveAndFlush(StreamSessionEntity.builder()
                .id(sessionId)
                .channelId(UUID.randomUUID())
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .title("Worker unavailable")
                .visibility(StreamVisibility.PUBLIC)
                .status(StreamSessionStatus.LIVE)
                .playbackPath("/live/" + sessionId + "/index.m3u8")
                .creationRequestId(UUID.randomUUID())
                .mediaPathState(MediaPathState.OPEN)
                .mediaSourceId(UUID.randomUUID())
                .mediaSourceType(MediaSourceType.RTMP_CONN)
                .startedAt(LocalDateTime.ofInstant(BASE, ZoneOffset.UTC).minusMinutes(10))
                .build());

        long startedAt = System.nanoTime();
        assertEquals(1, dispatch.dispatchDueJobs());
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        StreamSessionEntity stored = sessions.findById(session.getId()).orElseThrow();
        assertEquals(ThumbnailJobStatus.PENDING_DISPATCH, stored.getThumbnailJobStatus());
        assertNotNull(stored.getThumbnailJobId());
        assertEquals(BASE.plusSeconds(240), stored.getThumbnailJobDeadlineAt().toInstant(ZoneOffset.UTC));
        assertNull(stored.getThumbnailUpdatedAt());
        assertTrue(elapsedMillis < 5_000, "A failed connection must not block the scheduler for long");
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        Clock thumbnailTestClock() {
            return Clock.fixed(BASE, ZoneOffset.UTC);
        }
    }
}
