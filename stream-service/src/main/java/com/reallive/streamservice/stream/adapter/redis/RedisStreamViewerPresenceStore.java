package com.reallive.streamservice.stream.adapter.redis;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class RedisStreamViewerPresenceStore implements StreamViewerPresenceStore {

    static final Duration CLOSED_RETENTION = Duration.ofMinutes(10);
    static final int BATCH_SIZE = 100;
    static final int LEAVE_RETRIES = 8;
    static final DefaultRedisScript<Long> JOIN = script("join");
    static final DefaultRedisScript<Long> LEAVE = script("leave");
    static final DefaultRedisScript<Long> COUNT = script("count");
    static final DefaultRedisScript<Long> BEGIN_RECOVERY = script("begin-recovery");
    static final DefaultRedisScript<Long> COMPLETE_RECOVERY = script("complete-recovery");
    static final DefaultRedisScript<Long> MARK_CLOSED = script("mark-closed");
    static final DefaultRedisScript<Long> COMPLETE_CLOSE = script("complete-close");

    StringRedisTemplate redis;
    Clock streamClock;

    @Override
    public void join(UUID sessionId, UUID userId, UUID readerId) {
        var keys = keys(sessionId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(readerId);
        access(() -> addReader(keys, userId, readerId, ""));
    }

    @Override
    public void observe(UUID sessionId, UUID userId, UUID readerId, UUID generation) {
        var keys = keys(sessionId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(readerId);
        Objects.requireNonNull(generation);
        access(() -> addReader(keys, userId, readerId, generation.toString()));
    }

    @Override
    public void leave(UUID sessionId, UUID readerId) {
        var keys = keys(sessionId);
        String reader = Objects.requireNonNull(readerId).toString();
        access(() -> {
            for (int attempt = 0; attempt < LEAVE_RETRIES; attempt++) {
                String user = redis.<String, String>opsForHash().get(keys.readerUsers(), reader);
                String expectedUser = user == null ? "" : UUID.fromString(user).toString();
                long outcome = execute(LEAVE,
                        List.of(keys.state(), keys.closedReaders(), keys.readerUsers(),
                                keys.userReaders(expectedUser.isEmpty() ? "unknown" : expectedUser), keys.viewers()),
                        reader, expectedUser, Long.toString(streamClock.millis()),
                        Long.toString(CLOSED_RETENTION.toMillis()));
                if (outcome != 2) {
                    return null;
                }
            }
            throw unavailable();
        });
    }

    @Override
    public Long count(UUID sessionId) {
        var keys = keys(sessionId);
        return access(() -> {
            long count = execute(COUNT, List.of(keys.state(), keys.viewers()));
            return count < 0 ? null : count;
        });
    }

    @Override
    public void close(UUID sessionId) {
        var keys = keys(sessionId);
        access(() -> {
            String generation = UUID.randomUUID().toString();
            long marked = execute(MARK_CLOSED,
                    List.of(keys.state(), keys.viewers(), keys.readerUsers(), keys.closedReaders()),
                    generation, Long.toString(streamClock.millis()));
            if (marked == 0) {
                return null;
            }
            removeUserReaderSets(keys);
            execute(COMPLETE_CLOSE,
                    List.of(keys.state(), keys.viewers(), keys.readerUsers(), keys.closedReaders()),
                    generation, Long.toString(CLOSED_RETENTION.toSeconds()));
            return null;
        });
    }

    @Override
    public UUID beginRecovery(UUID sessionId) {
        var keys = keys(sessionId);
        return access(() -> {
            UUID generation = UUID.randomUUID();
            long outcome = execute(BEGIN_RECOVERY, List.of(keys.state(), keys.closedReaders()),
                    generation.toString(), Long.toString(streamClock.millis()),
                    Long.toString(CLOSED_RETENTION.toMillis()));
            return outcome == 1 ? generation : null;
        });
    }

    @Override
    public boolean completeRecovery(UUID sessionId, UUID generation) {
        var keys = keys(sessionId);
        String expected = Objects.requireNonNull(generation).toString();
        return access(() -> execute(COMPLETE_RECOVERY, List.of(keys.state()),
                expected, Long.toString(streamClock.millis())) == 1);
    }

    @Override
    public Map<UUID, UUID> readers(UUID sessionId) {
        var keys = keys(sessionId);
        return access(() -> {
            Map<UUID, UUID> readers = new LinkedHashMap<>();
            try (Cursor<Map.Entry<String, String>> cursor = redis.<String, String>opsForHash()
                    .scan(keys.readerUsers(), scanOptions())) {
                while (cursor.hasNext()) {
                    var entry = cursor.next();
                    readers.put(UUID.fromString(entry.getKey()), UUID.fromString(entry.getValue()));
                }
            }
            return Map.copyOf(readers);
        });
    }

    private Long addReader(PresenceKeys keys, UUID userId, UUID readerId, String expectedGeneration) {
        long outcome = execute(JOIN,
                List.of(keys.state(), keys.closedReaders(), keys.readerUsers(),
                        keys.userReaders(userId.toString()), keys.viewers()),
                UUID.randomUUID().toString(), userId.toString(), readerId.toString(),
                Long.toString(streamClock.millis()), Long.toString(CLOSED_RETENTION.toMillis()), expectedGeneration);
        if (outcome == -2) {
            throw new AppException(ErrorCode.STREAM_REQUEST_INVALID);
        }
        return outcome;
    }

    private void removeUserReaderSets(PresenceKeys keys) {
        List<String> batch = new ArrayList<>(BATCH_SIZE);
        try (Cursor<String> cursor = redis.opsForSet().scan(keys.viewers(), scanOptions())) {
            while (cursor.hasNext()) {
                batch.add(keys.userReaders(UUID.fromString(cursor.next()).toString()));
                if (batch.size() == BATCH_SIZE) {
                    redis.unlink(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            redis.unlink(batch);
        }
    }

    private long execute(DefaultRedisScript<Long> script, List<String> keys, String... arguments) {
        Long outcome = redis.execute(script, keys, (Object[]) arguments);
        if (outcome == null) {
            throw unavailable();
        }
        return outcome;
    }

    private <T> T access(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (AppException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private PresenceKeys keys(UUID sessionId) {
        return new PresenceKeys("stream:{" + Objects.requireNonNull(sessionId) + "}:");
    }

    private ScanOptions scanOptions() {
        return ScanOptions.scanOptions().count(BATCH_SIZE).build();
    }

    private static DefaultRedisScript<Long> script(String name) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource("redis/stream-viewer-presence/" + name + ".lua"));
        script.setResultType(Long.class);
        return script;
    }

    private AppException unavailable() {
        return new AppException(ErrorCode.VIEWER_PRESENCE_UNAVAILABLE);
    }

    private record PresenceKeys(
            String prefix
    ) {
        String state() {
            return prefix + "presence-state";
        }

        String viewers() {
            return prefix + "viewers";
        }

        String readerUsers() {
            return prefix + "reader-users";
        }

        String closedReaders() {
            return prefix + "closed-readers";
        }

        String userReaders(String userId) {
            return prefix + "user:" + userId + ":readers";
        }
    }
}
