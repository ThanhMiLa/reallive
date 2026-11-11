package com.reallive.streamservice.stream.adapter.redis;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class RedisStreamViewerPresenceStoreTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private RedisStreamViewerPresenceStore store;
    private MutableClock clock;
    private UUID sessionId;
    private UUID userId;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        sessionId = UUID.randomUUID();
        userId = UUID.randomUUID();
        clock = new MutableClock(Instant.parse("2026-11-11T00:00:00Z"));
        store = new RedisStreamViewerPresenceStore(redis, clock);
    }

    @Test
    void missingOrRecoveringStateIsUnknownUntilTheCurrentRecoveryCompletes() {
        assertThat(store.count(sessionId)).isNull();
        store.join(sessionId, userId, UUID.randomUUID());
        assertThat(store.count(sessionId)).isNull();

        UUID generation = store.beginRecovery(sessionId);
        assertThat(store.completeRecovery(sessionId, generation)).isTrue();
        assertThat(store.count(sessionId)).isEqualTo(1L);
    }

    @Test
    void emptyActivePresenceHasARealZeroCount() {
        activate(sessionId);
        assertThat(store.count(sessionId)).isZero();
        assertThat(redis.hasKey(key("viewers"))).isFalse();
    }

    @Test
    void manyReadersAndDuplicateCallbacksStillCountOneAccount() {
        activate(sessionId);
        List<UUID> readers = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            UUID reader = UUID.randomUUID();
            readers.add(reader);
            store.join(sessionId, userId, reader);
            store.join(sessionId, userId, reader);
        }
        assertThat(store.count(sessionId)).isEqualTo(1L);
        assertThat(store.readers(sessionId)).hasSize(100);

        for (int index = 0; index < 99; index++) {
            store.leave(sessionId, readers.get(index));
            store.leave(sessionId, readers.get(index));
            assertThat(store.count(sessionId)).isEqualTo(1L);
        }
        store.leave(sessionId, readers.getLast());
        store.leave(sessionId, readers.getLast());
        assertThat(store.count(sessionId)).isZero();
        assertThat(store.readers(sessionId)).isEmpty();
        assertThat(redis.hasKey(userReadersKey(userId))).isFalse();
    }

    @Test
    void accountMembershipIsIndependentForEachStreamSession() {
        UUID otherSession = UUID.randomUUID();
        activate(sessionId);
        activate(otherSession);
        store.join(sessionId, userId, UUID.randomUUID());
        store.join(sessionId, UUID.randomUUID(), UUID.randomUUID());
        store.join(otherSession, userId, UUID.randomUUID());

        assertThat(store.count(sessionId)).isEqualTo(2L);
        assertThat(store.count(otherSession)).isEqualTo(1L);
        store.close(sessionId);
        assertThat(store.count(otherSession)).isEqualTo(1L);
    }

    @Test
    void unreadBeforeReadLeavesATombstoneThatBlocksDelayedRead() {
        activate(sessionId);
        UUID reader = UUID.randomUUID();
        store.leave(sessionId, reader);
        store.join(sessionId, userId, reader);

        assertThat(store.readers(sessionId)).isEmpty();
        assertThat(store.count(sessionId)).isZero();
        assertThat(redis.opsForZSet().score(key("closed-readers"), reader.toString()))
                .isEqualTo((double) clock.millis());
        assertThat(redis.getExpire(key("closed-readers"))).isEqualTo(-1L);
    }

    @Test
    void unreadWithoutTrackedPresenceKeepsOnlyABoundedTombstone() {
        UUID reader = UUID.randomUUID();
        store.leave(sessionId, reader);
        assertThat(redis.getExpire(key("closed-readers"))).isBetween(590L, 600L);
        store.join(sessionId, userId, reader);
        assertThat(store.readers(sessionId)).isEmpty();
    }

    @Test
    void readerCannotBeReassignedToAnotherAccount() {
        activate(sessionId);
        UUID reader = UUID.randomUUID();
        store.join(sessionId, userId, reader);

        assertThatThrownBy(() -> store.join(sessionId, UUID.randomUUID(), reader))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.STREAM_REQUEST_INVALID));
        assertThat(store.readers(sessionId)).containsExactlyEntriesOf(Map.of(reader, userId));
        assertThat(store.count(sessionId)).isEqualTo(1L);
    }

    @Test
    void staleRecoveryCannotObserveOrPublishAnotherGeneration() {
        UUID staleGeneration = store.beginRecovery(sessionId);
        UUID currentGeneration = store.beginRecovery(sessionId);
        UUID staleReader = UUID.randomUUID();
        UUID currentReader = UUID.randomUUID();

        store.observe(sessionId, userId, staleReader, staleGeneration);
        assertThat(store.completeRecovery(sessionId, staleGeneration)).isFalse();
        assertThat(store.count(sessionId)).isNull();
        store.observe(sessionId, userId, currentReader, currentGeneration);
        assertThat(store.completeRecovery(sessionId, currentGeneration)).isTrue();

        assertThat(store.readers(sessionId)).containsExactlyEntriesOf(Map.of(currentReader, userId));
        assertThat(store.count(sessionId)).isEqualTo(1L);
    }

    @Test
    void oldRecoveryCannotRecreateStateAfterRedisLosesIt() {
        UUID generation = store.beginRecovery(sessionId);
        redis.delete(key("presence-state"));

        store.observe(sessionId, userId, UUID.randomUUID(), generation);
        assertThat(store.completeRecovery(sessionId, generation)).isFalse();
        assertThat(redis.hasKey(key("presence-state"))).isFalse();
        assertThat(store.readers(sessionId)).isEmpty();
    }

    @Test
    void tombstoneAlsoFencesAnObservationFromTheCurrentRecovery() {
        UUID generation = store.beginRecovery(sessionId);
        UUID reader = UUID.randomUUID();
        store.leave(sessionId, reader);
        store.observe(sessionId, userId, reader, generation);
        store.completeRecovery(sessionId, generation);

        assertThat(store.count(sessionId)).isZero();
        assertThat(store.readers(sessionId)).isEmpty();
    }

    @Test
    void activeMembershipsHaveNoShortTtl() {
        activate(sessionId);
        store.join(sessionId, userId, UUID.randomUUID());

        for (String suffix : List.of("presence-state", "viewers", "reader-users", "user:" + userId + ":readers")) {
            assertThat(redis.getExpire(key(suffix))).isEqualTo(-1L);
        }
    }

    @Test
    void closedSessionRejectsAllFutureJoinsAndRecoveryAndCleansEveryBatch() {
        UUID generation = store.beginRecovery(sessionId);
        List<UUID> users = new ArrayList<>();
        for (int index = 0; index < 205; index++) {
            UUID user = UUID.randomUUID();
            users.add(user);
            store.join(sessionId, user, UUID.randomUUID());
        }
        store.close(sessionId);

        assertThat(store.count(sessionId)).isZero();
        assertThat(store.beginRecovery(sessionId)).isNull();
        assertThat(store.completeRecovery(sessionId, generation)).isFalse();
        store.join(sessionId, userId, UUID.randomUUID());
        store.observe(sessionId, userId, UUID.randomUUID(), generation);
        assertThat(store.readers(sessionId)).isEmpty();
        assertThat(redis.hasKey(key("viewers"))).isFalse();
        for (UUID user : users) {
            assertThat(redis.hasKey(userReadersKey(user))).isFalse();
        }
        assertThat(redis.getExpire(key("presence-state"))).isBetween(599L, 600L);

        redis.expire(key("presence-state"), Duration.ofSeconds(590));
        store.close(sessionId);
        assertThat(redis.getExpire(key("presence-state"))).isBetween(589L, 590L);
    }

    @Test
    void failedCleanupRetainsTheIndexAndCanBeRetried() {
        activate(sessionId);
        store.join(sessionId, userId, UUID.randomUUID());
        StringRedisTemplate broken = mock(StringRedisTemplate.class, delegatesTo(redis));
        doThrow(new RedisConnectionFailureException("offline during cleanup"))
                .when(broken).unlink(anyCollection());
        var failingStore = new RedisStreamViewerPresenceStore(broken, clock);

        assertThatThrownBy(() -> failingStore.close(sessionId)).isInstanceOf(AppException.class);
        assertThat(store.count(sessionId)).isZero();
        assertThat(redis.getExpire(key("presence-state"))).isEqualTo(-1L);
        assertThat(store.readers(sessionId)).hasSize(1);

        store.close(sessionId);
        assertThat(store.readers(sessionId)).isEmpty();
        assertThat(redis.hasKey(userReadersKey(userId))).isFalse();
        assertThat(redis.getExpire(key("presence-state"))).isBetween(599L, 600L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unreadRetriesWhenReadArrivesBetweenMappingLookupAndLua() {
        activate(sessionId);
        UUID reader = UUID.randomUUID();
        StringRedisTemplate racing = mock(StringRedisTemplate.class, delegatesTo(redis));
        HashOperations<String, String, String> hashes = mock(HashOperations.class);
        when(racing.<String, String>opsForHash()).thenReturn(hashes);
        AtomicBoolean injected = new AtomicBoolean();
        when(hashes.get(key("reader-users"), reader.toString())).thenAnswer(invocation -> {
            if (injected.compareAndSet(false, true)) {
                store.join(sessionId, userId, reader);
                return null;
            }
            return redis.<String, String>opsForHash().get(key("reader-users"), reader.toString());
        });

        new RedisStreamViewerPresenceStore(racing, clock).leave(sessionId, reader);

        assertThat(store.count(sessionId)).isZero();
        assertThat(store.readers(sessionId)).isEmpty();
        store.join(sessionId, userId, reader);
        assertThat(store.count(sessionId)).isZero();
    }

    @Test
    void newReadersRacingWithOldUnreadsDoNotRemoveTheActiveViewer() throws Exception {
        activate(sessionId);
        UUID oldReader = UUID.randomUUID();
        store.join(sessionId, userId, oldReader);
        List<UUID> activeReaders = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> operations = new ArrayList<>();
            for (int index = 0; index < 40; index++) {
                UUID reader = UUID.randomUUID();
                activeReaders.add(reader);
                operations.add(executor.submit(() -> {
                    start.await();
                    store.join(sessionId, userId, reader);
                    return null;
                }));
                operations.add(executor.submit(() -> {
                    start.await();
                    store.leave(sessionId, oldReader);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> operation : operations) {
                operation.get(15, TimeUnit.SECONDS);
            }
        }
        assertThat(store.readers(sessionId)).hasSize(activeReaders.size());
        assertThat(store.count(sessionId)).isEqualTo(1L);
    }

    @Test
    void expiredTombstonesArePrunedWithoutWaitingForRedisKeyExpiration() {
        activate(sessionId);
        UUID reader = UUID.randomUUID();
        store.leave(sessionId, reader);
        clock.advance(Duration.ofMinutes(11));
        store.beginRecovery(sessionId);

        assertThat(redis.opsForZSet().score(key("closed-readers"), reader.toString())).isNull();
    }

    @Test
    void redisFailuresBecomeThePresenceUnavailableApplicationError() {
        StringRedisTemplate unavailable = mock(StringRedisTemplate.class);
        when(unavailable.opsForHash()).thenThrow(new RedisConnectionFailureException("offline"));

        assertThatThrownBy(() -> new RedisStreamViewerPresenceStore(unavailable, clock).readers(sessionId))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VIEWER_PRESENCE_UNAVAILABLE));
    }

    private void activate(UUID streamId) {
        UUID generation = store.beginRecovery(streamId);
        assertThat(store.completeRecovery(streamId, generation)).isTrue();
    }

    private String key(String suffix) {
        return "stream:{" + sessionId + "}:" + suffix;
    }

    private String userReadersKey(UUID user) {
        return key("user:" + user + ":readers");
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
