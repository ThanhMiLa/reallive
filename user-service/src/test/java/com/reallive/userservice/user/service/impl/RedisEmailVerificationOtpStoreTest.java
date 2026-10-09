package com.reallive.userservice.user.service.impl;

import com.reallive.userservice.user.model.EmailVerificationCooldownResult;
import com.reallive.userservice.user.model.EmailVerificationOtpCheckResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "user.test.redis-port", matches = "[0-9]+")
class RedisEmailVerificationOtpStoreTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisEmailVerificationOtpStore store;
    private UUID userId;

    @BeforeAll
    void connectToRedis() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1",
                Integer.parseInt(System.getProperty("user.test.redis-port")));
        configuration.setDatabase(15);
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        store = new RedisEmailVerificationOtpStore(redisTemplate);
        ReflectionTestUtils.setField(store, "ttlSeconds", 60L);
    }

    @BeforeEach
    void createUser() {
        userId = UUID.randomUUID();
    }

    @AfterEach
    void removeTestKey() {
        redisTemplate.delete(key());
    }

    @AfterAll
    void closeConnection() {
        connectionFactory.destroy();
    }

    @Test
    void savesHashWithSixtySecondTtlAndReportsCooldown() {
        assertFalse(store.hasCooldown(userId));

        EmailVerificationCooldownResult result = store.save(userId, "otp-hash");

        assertTrue(result.acquired());
        assertEquals(Duration.ZERO, result.retryAfter());
        assertEquals("otp-hash", redisTemplate.opsForValue().get(key()));
        assertTrue(store.hasCooldown(userId));
        assertTrue(remainingMillis() > 59000);
        assertTrue(remainingMillis() <= 60000);
    }

    @Test
    void rejectsResendWithoutReplacingHashOrExtendingTtl() {
        store.save(userId, "original-hash");
        redisTemplate.expire(key(), Duration.ofSeconds(10));
        long before = remainingMillis();

        EmailVerificationCooldownResult result = store.save(userId, "replacement-hash");

        assertFalse(result.acquired());
        assertTrue(result.retryAfter().toMillis() > 0);
        assertTrue(result.retryAfter().toMillis() <= before);
        assertTrue(remainingMillis() <= before);
        assertEquals("original-hash", redisTemplate.opsForValue().get(key()));
    }

    @Test
    void wrongOtpPreservesHashAndTtlWithoutAttemptLimit() {
        store.save(userId, "correct-hash");
        redisTemplate.expire(key(), Duration.ofSeconds(10));
        long before = remainingMillis();

        for (int attempt = 0; attempt < 10; attempt++) {
            assertEquals(EmailVerificationOtpCheckResult.INVALID, store.consume(userId, "wrong-hash"));
        }

        assertEquals("correct-hash", redisTemplate.opsForValue().get(key()));
        assertTrue(remainingMillis() <= before);
        assertEquals(EmailVerificationOtpCheckResult.VALID, store.consume(userId, "correct-hash"));
    }

    @Test
    void correctOtpDeletesKeyImmediatelyAndCannotBeReused() {
        store.save(userId, "correct-hash");

        assertEquals(EmailVerificationOtpCheckResult.VALID, store.consume(userId, "correct-hash"));
        assertNull(redisTemplate.opsForValue().get(key()));
        assertFalse(store.hasCooldown(userId));
        assertEquals(EmailVerificationOtpCheckResult.NOT_FOUND, store.consume(userId, "correct-hash"));
    }

    @Test
    void expiredKeyRejectsOldOtpAndAllowsNewRequest() {
        RedisEmailVerificationOtpStore shortLivedStore = new RedisEmailVerificationOtpStore(redisTemplate);
        ReflectionTestUtils.setField(shortLivedStore, "ttlSeconds", 1L);
        shortLivedStore.save(userId, "old-hash");
        await().atMost(Duration.ofSeconds(5)).until(() -> !store.hasCooldown(userId));

        assertEquals(EmailVerificationOtpCheckResult.NOT_FOUND, store.consume(userId, "old-hash"));
        assertTrue(store.save(userId, "new-hash").acquired());
        assertEquals(EmailVerificationOtpCheckResult.INVALID, store.consume(userId, "old-hash"));
        assertEquals("new-hash", redisTemplate.opsForValue().get(key()));
    }

    @Test
    void cleanupDoesNotDeleteNewerDifferentHash() {
        store.save(userId, "old-hash");
        store.consume(userId, "old-hash");
        store.save(userId, "new-hash");

        store.deleteIfCurrent(userId, "old-hash");

        assertEquals("new-hash", redisTemplate.opsForValue().get(key()));
        store.deleteIfCurrent(userId, "new-hash");
        assertFalse(store.hasCooldown(userId));
    }

    @Test
    void onlyOneConcurrentRequestCanSaveAnOtp() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return store.save(userId, "first-hash");
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return store.save(userId, "second-hash");
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            EmailVerificationCooldownResult firstResult = first.get(10, TimeUnit.SECONDS);
            EmailVerificationCooldownResult secondResult = second.get(10, TimeUnit.SECONDS);

            assertTrue(firstResult.acquired() != secondResult.acquired());
            assertEquals(firstResult.acquired() ? "first-hash" : "second-hash",
                    redisTemplate.opsForValue().get(key()));
        }
    }

    @Test
    void onlyOneConcurrentRequestCanConsumeAnOtp() throws Exception {
        store.save(userId, "correct-hash");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return store.consume(userId, "correct-hash");
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return store.consume(userId, "correct-hash");
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);

            assertTrue(firstResult != secondResult);
            assertTrue(firstResult == EmailVerificationOtpCheckResult.VALID
                    || secondResult == EmailVerificationOtpCheckResult.VALID);
            assertTrue(firstResult == EmailVerificationOtpCheckResult.NOT_FOUND
                    || secondResult == EmailVerificationOtpCheckResult.NOT_FOUND);
            assertFalse(store.hasCooldown(userId));
        }
    }

    private String key() {
        return "otp:email-verification:cooldown:" + userId;
    }

    private long remainingMillis() {
        return redisTemplate.getExpire(key(), TimeUnit.MILLISECONDS);
    }
}
