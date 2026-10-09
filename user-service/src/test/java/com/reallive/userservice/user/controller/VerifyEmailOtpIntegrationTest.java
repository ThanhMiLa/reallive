package com.reallive.userservice.user.controller;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.security.CustomJwtDecoder;
import com.reallive.userservice.user.adapter.kafka.EmailVerificationEmailPublisher;
import com.reallive.userservice.user.adapter.redis.EmailVerificationOtpStore;
import com.reallive.userservice.user.dto.message.EmailVerificationRequestedMessage;
import com.reallive.userservice.user.util.EmailVerificationOtpHasher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:email_verify_flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.open-in-view=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "email-verification.otp.hmac-key=isolated-verify-integration-secret",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.database=15"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "user.test.redis-port", matches = "[0-9]+")
class VerifyEmailOtpIntegrationTest {

    private static final String ENDPOINT = "/api/users/me/email-verification/verify";
    private static final String OTP = "012345";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository repository;
    @Autowired
    private EmailVerificationOtpStore store;
    @Autowired
    private EmailVerificationOtpHasher hasher;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private CustomJwtDecoder decoder;
    @MockitoBean
    private EmailVerificationEmailPublisher publisher;

    private MockMvc mvc;
    private UserEntity user;

    @DynamicPropertySource
    static void redisPort(DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.port", () -> System.getProperty("user.test.redis-port"));
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        user = repository.saveAndFlush(UserEntity.builder().username("verify-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@example.com").passwordHash("test-hash").build());
        authenticate("verify-token", user);
    }

    @AfterEach
    void cleanUp() {
        redis.delete(key());
    }

    @Test
    void requestedOtpVerifiesEmailAndCannotBeUsedAgain() throws Exception {
        mvc.perform(post("/api/users/me/email-verification/otp").cookie(cookie()))
                .andExpect(status().isAccepted());
        ArgumentCaptor<EmailVerificationRequestedMessage> captor = ArgumentCaptor.forClass(
                EmailVerificationRequestedMessage.class);
        verify(publisher).publish(captor.capture());
        assertTrue(redis.hasKey(key()));

        verifyOtp(captor.getValue().getOtp(), "verify-token", 200, 1000);

        assertTrue(repository.findById(user.getId()).orElseThrow().isEmailVerified());
        assertNull(redis.opsForValue().get(key()));
        verifyOtp(captor.getValue().getOtp(), "verify-token", 400, 1025);
        assertTrue(repository.findById(user.getId()).orElseThrow().isEmailVerified());
    }

    @Test
    void wrongOtpPreservesHashAndExpiryAndAllowsTheCorrectOtp() throws Exception {
        saveOtp();
        String originalHash = redis.opsForValue().get(key());
        long originalTtl = redis.getExpire(key(), TimeUnit.MILLISECONDS);

        verifyOtp("654321", "verify-token", 400, 1024);

        assertFalse(repository.findById(user.getId()).orElseThrow().isEmailVerified());
        assertEquals(originalHash, redis.opsForValue().get(key()));
        long remainingTtl = redis.getExpire(key(), TimeUnit.MILLISECONDS);
        assertTrue(remainingTtl > 0 && remainingTtl <= originalTtl);
        verifyOtp(OTP, "verify-token", 200, 1000);
        assertNull(redis.opsForValue().get(key()));
    }

    @Test
    void expiredOtpCannotVerifyEmail() throws Exception {
        saveOtp();
        assertTrue(redis.expire(key(), Duration.ZERO));
        verifyOtp(OTP, "verify-token", 400, 1025);
        assertFalse(repository.findById(user.getId()).orElseThrow().isEmailVerified());
        assertNull(redis.opsForValue().get(key()));
    }

    @Test
    void missingOtpCannotVerifyEmail() throws Exception {
        verifyOtp(OTP, "verify-token", 400, 1025);
        assertFalse(repository.findById(user.getId()).orElseThrow().isEmailVerified());
    }

    @Test
    void otpForAnotherUserCannotBeConsumed() throws Exception {
        saveOtp();
        UserEntity other = repository.saveAndFlush(UserEntity.builder().username("other-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@example.com").passwordHash("test-hash").build());
        authenticate("other-token", other);

        verifyOtp(OTP, "other-token", 400, 1025);

        assertFalse(repository.findById(other.getId()).orElseThrow().isEmailVerified());
        assertFalse(repository.findById(user.getId()).orElseThrow().isEmailVerified());
        assertEquals(hasher.hash(user.getId(), user.getEmail(), OTP), redis.opsForValue().get(key()));
    }

    @Test
    void changedDatabaseEmailInvalidatesOtpDespiteStaleJwtAndClientEmail() throws Exception {
        saveOtp();
        String originalHash = redis.opsForValue().get(key());
        String oldEmail = user.getEmail();
        user.setEmail(UUID.randomUUID() + "@changed.example.com");
        repository.saveAndFlush(user);

        mvc.perform(post(ENDPOINT).cookie(cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"otp\":\"012345\",\"email\":\"" + oldEmail + "\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1024));

        assertFalse(repository.findById(user.getId()).orElseThrow().isEmailVerified());
        assertEquals(originalHash, redis.opsForValue().get(key()));
    }

    @Test
    void concurrentVerificationsAllowExactlyOneSuccess() throws Exception {
        saveOtp();
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> verifyAfterBarrier(ready, start));
            var second = executor.submit(() -> verifyAfterBarrier(ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> statuses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertTrue(statuses.contains(200) && statuses.contains(400));
        }
        assertTrue(repository.findById(user.getId()).orElseThrow().isEmailVerified());
        assertNull(redis.opsForValue().get(key()));
    }

    @Test
    void databaseCommitFailureRollsBackVerificationWithoutRestoringConsumedOtp() {
        saveOtp();
        String constraint = "reject_verify_" + user.getId().toString().replace("-", "");
        jdbc.execute("alter table users add constraint " + constraint
                + " check (id <> '" + user.getId() + "' or email_verified = false)");
        try {
            assertThrows(ServletException.class, () -> verifyOtp(OTP, "verify-token", 200, 1000));
            assertFalse(repository.findById(user.getId()).orElseThrow().isEmailVerified());
            assertNull(redis.opsForValue().get(key()));
        } finally {
            jdbc.execute("alter table users drop constraint " + constraint);
        }
    }

    private void saveOtp() {
        assertTrue(store.save(user.getId(), hasher.hash(user.getId(), user.getEmail(), OTP)).acquired());
    }

    private void verifyOtp(String otp, String token, int expectedStatus, int expectedCode) throws Exception {
        mvc.perform(post(ENDPOINT).cookie(new Cookie("access_token", token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"otp\":\"" + otp + "\"}"))
                .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(expectedCode));
    }

    private int verifyAfterBarrier(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        var response = mvc.perform(post(ENDPOINT).cookie(cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"otp\":\"012345\"}"))
                .andReturn().getResponse();
        return response.getStatus();
    }

    private void authenticate(String token, UserEntity identity) {
        when(decoder.decode(token)).thenReturn(Jwt.withTokenValue(token).header("alg", "HS512")
                .subject(identity.getId().toString()).claim("scope", "USER")
                .claim("email", identity.getEmail()).claim("emailVerified", false).build());
    }

    private Cookie cookie() {
        return new Cookie("access_token", "verify-token");
    }

    private String key() {
        return "otp:email-verification:cooldown:" + user.getId();
    }
}
