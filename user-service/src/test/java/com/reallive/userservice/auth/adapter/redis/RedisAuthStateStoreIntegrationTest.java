package com.reallive.userservice.auth.adapter.redis;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "auth.test.redis-port", matches = "[0-9]+")
class RedisAuthStateStoreIntegrationTest {

    private final UUID userId = UUID.randomUUID();
    private final List<String> cleanup = new ArrayList<>();
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisAuthStateStore store;

    @BeforeEach
    void connect() {
        var configuration = new RedisStandaloneConfiguration("127.0.0.1",
                Integer.parseInt(System.getProperty("auth.test.redis-port")));
        configuration.setDatabase(14);
        factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        store = new RedisAuthStateStore(redis);
        cleanup.addAll(List.of(authorities(), status(), "auth:revision:" + userId));
    }

    @AfterEach
    void close() {
        try {
            if (redis != null) {
                redis.delete(cleanup);
            }
        } finally {
            if (factory != null) {
                factory.destroy();
            }
        }
    }

    @Test
    void publishesBothValuesAndExtendsButNeverShortensTheirTtl() {
        store.publish(snapshot(0, "ACTIVE", "[\"ROLE_USER\"]", 60), List.of(), false);
        assertEquals("ACTIVE", redis.opsForValue().get(status()));
        assertEquals("[\"ROLE_USER\"]", redis.opsForValue().get(authorities()));
        store.publish(snapshot(1, "ACTIVE", "[\"ROLE_STREAMER\"]", 5), List.of(), false);
        assertTrue(redis.getExpire(authorities(), TimeUnit.MILLISECONDS) > 55000);
        assertTrue(redis.getExpire(status(), TimeUnit.MILLISECONDS) > 55000);
        store.publish(snapshot(1, "ACTIVE", "[\"ROLE_STREAMER\"]", 120), List.of(), true);
        assertTrue(redis.getExpire(authorities(), TimeUnit.MILLISECONDS) > 115000);
    }

    @Test
    void olderLoginCannotOverwriteNewerRoleOrAccountStatus() {
        store.publish(snapshot(2, "BANNED", "[\"ROLE_USER\"]", 60), List.of(), false);
        store.publish(snapshot(1, "ACTIVE", "[\"ROLE_ADMIN\"]", 60), List.of(), false);
        assertEquals("BANNED", redis.opsForValue().get(status()));
        assertEquals("[\"ROLE_USER\"]", redis.opsForValue().get(authorities()));
    }

    @Test
    void staleIssuanceCannotBypassANewerBanOrConsumeItsRefreshToken() {
        store.publish(snapshot(2, "BANNED", "[]", 60), List.of(), false);
        TokenRevocation refresh = refresh();
        AppException failure = assertThrows(AppException.class,
                () -> store.publish(snapshot(1, "ACTIVE", "[]", 60), List.of(refresh), true));
        assertEquals(ErrorCode.USER_NOT_ACTIVE, failure.getErrorCode());
        assertFalse(redis.hasKey(refresh.key()));
    }

    @Test
    void publicationFailureDoesNotConsumeRefreshOrPartiallyWriteAuthorities() {
        redis.opsForList().leftPush(status(), "wrong-type");
        TokenRevocation refresh = refresh();
        AppException failure = assertThrows(AppException.class,
                () -> store.publish(snapshot(0, "ACTIVE", "[]", 60), List.of(refresh), true));
        assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE, failure.getErrorCode());
        assertFalse(redis.hasKey(refresh.key()));
        assertFalse(redis.hasKey(authorities()));
        assertEquals("wrong-type", redis.opsForList().index(status(), 0));
    }

    @Test
    void concurrentRefreshIsConsumedExactlyOnceWithAtomicPublication() throws Exception {
        TokenRevocation refresh = refresh();
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> consume(refresh, start));
            var second = executor.submit(() -> consume(refresh, start));
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
        }
        assertEquals("1", redis.opsForValue().get(refresh.key()));
        assertEquals("ACTIVE", redis.opsForValue().get(status()));
    }

    @Test
    void refreshKeepsCurrentAuthoritiesWhenItsSnapshotIsOlder() {
        store.publish(snapshot(2, "ACTIVE", "[\"ROLE_STREAMER\"]", 60), List.of(), false);
        TokenRevocation refresh = refresh();
        store.publish(snapshot(1, "ACTIVE", "[\"ROLE_USER\"]", 120), List.of(refresh), true);
        assertEquals("[\"ROLE_STREAMER\"]", redis.opsForValue().get(authorities()));
        assertEquals("1", redis.opsForValue().get(refresh.key()));
        assertTrue(redis.getExpire(authorities(), TimeUnit.MILLISECONDS) > 115000);
    }

    @Test
    void logoutRevokesOnlySessionTokensAndRetainsAccountState() {
        store.publish(snapshot(0, "ACTIVE", "[\"ROLE_USER\"]", 60), List.of(), false);
        TokenRevocation token = refresh();
        store.revoke(List.of(token));
        assertEquals("1", redis.opsForValue().get(token.key()));
        assertEquals("ACTIVE", redis.opsForValue().get(status()));
        assertEquals("[\"ROLE_USER\"]", redis.opsForValue().get(authorities()));
        assertEquals(ErrorCode.UNAUTHENTICATED,
                assertThrows(AppException.class, () -> store.revoke(List.of(token))).getErrorCode());
    }

    private int consume(TokenRevocation refresh, CyclicBarrier start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            store.publish(snapshot(0, "ACTIVE", "[]", 60), List.of(refresh), true);
            return 1;
        } catch (AppException exception) {
            assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
            return 0;
        }
    }

    private AuthStateSnapshot snapshot(long revision, String state, String permissions, long ttlSeconds) {
        return new AuthStateSnapshot(userId, revision, permissions, state, Duration.ofSeconds(ttlSeconds));
    }

    private TokenRevocation refresh() {
        String key = "auth:revoked:refresh:" + UUID.randomUUID();
        cleanup.add(key);
        return new TokenRevocation(key, Duration.ofSeconds(60));
    }

    private String authorities() {
        return "auth:authorities:" + userId;
    }

    private String status() {
        return "auth:status:" + userId;
    }
}
