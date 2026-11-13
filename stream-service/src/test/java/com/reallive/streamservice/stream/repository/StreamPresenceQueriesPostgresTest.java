package com.reallive.streamservice.stream.repository;

import com.reallive.streamservice.stream.PostgresIntegrationTestSupport;
import com.reallive.streamservice.stream.entity.StreamSessionEntity;
import com.reallive.streamservice.stream.entity.enums.MediaPathState;
import com.reallive.streamservice.stream.entity.enums.MediaSourceType;
import com.reallive.streamservice.stream.entity.enums.StreamSessionStatus;
import com.reallive.streamservice.stream.entity.enums.StreamVisibility;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(showSql = false)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StreamPresenceQueriesPostgresTest extends PostgresIntegrationTestSupport {

    private static final String SCHEMA = newSchema();

    @Autowired
    StreamSessionRepository repository;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        configureDatabase(registry, SCHEMA);
    }

    @AfterAll
    static void cleanup() throws SQLException {
        dropSchema(SCHEMA);
    }

    @Test
    void batchStatusProjectionUsesOneSqlStatementAndDoesNotLoadEntities() {
        var saved = repository.saveAllAndFlush(IntStream.range(0, 104)
                .mapToObj(index -> session(StreamSessionStatus.RECONNECTING)).toList());
        var ended = session(StreamSessionStatus.RECONNECTING);
        ended.setStatus(StreamSessionStatus.ENDED);
        ended.setEndedAt(LocalDateTime.now(ZoneOffset.UTC));
        ended.setDisconnectedAt(null);
        ended.setReconnectDeadlineAt(null);
        ended.setMediaPathState(MediaPathState.CLOSED);
        ended = repository.saveAndFlush(ended);
        var ids = new ArrayList<>(saved.stream().map(StreamSessionEntity::getId).toList());
        ids.add(ended.getId());
        ids.add(UUID.randomUUID());
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var snapshots = repository.findStatusSnapshotsByIdIn(ids);
            assertEquals(105, snapshots.size());
            var statuses = snapshots.stream().collect(Collectors.toMap(snapshot -> snapshot.id(), snapshot -> snapshot.status()));
            assertEquals(StreamSessionStatus.ENDED, statuses.get(ended.getId()));
            assertTrue(saved.stream().allMatch(item -> statuses.get(item.getId()) == StreamSessionStatus.RECONNECTING));
            assertEquals(1, statistics.getPrepareStatementCount(), "Batch lookup must not issue one query per ID");
            assertEquals(0, statistics.getEntityLoadCount(), "Status lookup must select only projection fields");
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void activePresenceSlicesUseOneQueryPerSliceWithoutCountOrEntityLoading() {
        repository.saveAllAndFlush(IntStream.range(0, 101)
                .mapToObj(index -> session(StreamSessionStatus.LIVE)).toList());
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var first = repository.findPresenceSessionsByStatusIn(List.of(StreamSessionStatus.LIVE), PageRequest.of(0, 100));
            assertEquals(100, first.getNumberOfElements());
            assertTrue(first.hasNext());
            assertTrue(first.stream().allMatch(item -> item.id() != null && item.ownerUserId() != null));
            assertEquals(1, statistics.getPrepareStatementCount(), "Slice must not issue a COUNT query");
            var last = repository.findPresenceSessionsByStatusIn(List.of(StreamSessionStatus.LIVE), PageRequest.of(1, 100));
            assertEquals(1, last.getNumberOfElements());
            assertFalse(last.hasNext());
            assertEquals(2, statistics.getPrepareStatementCount());
            assertEquals(0, statistics.getEntityLoadCount());
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private StreamSessionEntity session(StreamSessionStatus status) {
        boolean live = status == StreamSessionStatus.LIVE;
        boolean reconnecting = status == StreamSessionStatus.RECONNECTING;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return StreamSessionEntity.builder()
                .channelId(UUID.randomUUID())
                .streamerId(UUID.randomUUID())
                .ownerUserId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .title("Presence query test")
                .visibility(StreamVisibility.PUBLIC)
                .status(status)
                .playbackPath("/live/" + UUID.randomUUID() + "/index.m3u8")
                .creationRequestId(UUID.randomUUID())
                .mediaPathState(MediaPathState.OPEN)
                .mediaSourceId(live ? UUID.randomUUID() : null)
                .mediaSourceType(live ? MediaSourceType.RTMP_CONN : null)
                .startedAt(live || reconnecting ? now.minusMinutes(5) : null)
                .disconnectedAt(reconnecting ? now : null)
                .reconnectDeadlineAt(reconnecting ? now.plusSeconds(60) : null)
                .build();
    }
}
