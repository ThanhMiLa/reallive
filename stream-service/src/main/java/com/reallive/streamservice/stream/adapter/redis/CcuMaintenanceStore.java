package com.reallive.streamservice.stream.adapter.redis;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CcuMaintenanceStore {
    StringRedisTemplate redis;
    private static final String LEASE = "ccu:reconcile:lease";
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end", Long.class);

    public boolean acquire(String owner) { return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LEASE, owner, Duration.ofSeconds(60))); }
    public void release(String owner) { redis.execute(RELEASE, List.of(LEASE), owner); }

    public Set<UUID> trackedSessions(long deadlineNanos) {
        Set<UUID> result = new HashSet<>();
        try (var cursor = redis.scan(ScanOptions.scanOptions().match("stream:{*}:presence-state").count(100).build())) {
            while (cursor.hasNext() && System.nanoTime() < deadlineNanos) {
                String key = cursor.next();
                try { result.add(UUID.fromString(key.substring(key.indexOf('{') + 1, key.indexOf('}')))); }
                catch (IllegalArgumentException ignored) { /* Ignore keys outside the UUID namespace. */ }
            }
        }
        return result;
    }
}
