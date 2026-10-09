package com.reallive.userservice.user.controller;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.security.CustomJwtDecoder;
import com.reallive.userservice.user.dto.message.EmailVerificationRequestedMessage;
import com.reallive.userservice.user.dto.response.EmailVerificationCooldownResult;
import com.reallive.userservice.user.exception.EmailVerificationPublishException;
import com.reallive.userservice.user.adapter.kafka.EmailVerificationEmailPublisher;
import com.reallive.userservice.user.adapter.redis.EmailVerificationOtpStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.UUID;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:email_otp_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.open-in-view=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "email-verification.otp.hmac-key=test-http-otp-secret-not-a-real-key"
})
@ActiveProfiles("test")
class EmailVerificationControllerTest {

    private static final String ENDPOINT = "/api/users/me/email-verification/otp";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private CustomJwtDecoder decoder;
    @MockitoBean
    private EmailVerificationOtpStore store;
    @MockitoBean
    private EmailVerificationEmailPublisher publisher;

    private MockMvc mvc;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        user = repository.saveAndFlush(UserEntity.builder().username("otp-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@example.com").passwordHash("test-password-hash").build());
        authenticate(user.getId().toString());
        when(store.save(any(), anyString())).thenReturn(new EmailVerificationCooldownResult(true, Duration.ZERO));
    }

    @Test
    void anonymousRequestReturns401WithoutRedisOrKafka() throws Exception {
        mvc.perform(post(ENDPOINT)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
        verifyNoInteractions(store, publisher);
    }

    @Test
    void expiredOrInvalidTokenReturns401() throws Exception {
        when(decoder.decode("otp-access-token")).thenThrow(new BadJwtException("expired"));
        mvc.perform(post(ENDPOINT).cookie(cookie())).andExpect(status().isUnauthorized());
        verifyNoInteractions(store, publisher);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-uuid"})
    void invalidPrincipalIdentityReturns401(String subject) throws Exception {
        authenticate(subject);
        mvc.perform(post(ENDPOINT).cookie(cookie())).andExpect(status().isUnauthorized());
        verifyNoInteractions(store, publisher);
    }

    @Test
    void acceptedResponseContainsOnlyExpiryAndDoesNotModifyUserData() throws Exception {
        long roleRows = jdbc.queryForObject("select count(*) from user_roles where user_id = ?", Long.class, user.getId());
        long streamerRows = jdbc.queryForObject("select count(*) from streamers where user_id = ?", Long.class, user.getId());
        mvc.perform(post(ENDPOINT).cookie(cookie()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value(202))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result", aMapWithSize(1)))
                .andExpect(jsonPath("$.result.expiresAt").isString())
                .andExpect(jsonPath("$.result.otp").doesNotExist());
        assertFalse(repository.findById(user.getId()).orElseThrow().isEmailVerified());
        assertEquals(roleRows, jdbc.queryForObject("select count(*) from user_roles where user_id = ?", Long.class, user.getId()));
        assertEquals(streamerRows, jdbc.queryForObject("select count(*) from streamers where user_id = ?", Long.class, user.getId()));
    }

    @Test
    void recipientAndIdentityComeFromDbAndPrincipalDespiteClientBodyAndStaleClaims() throws Exception {
        user.setEmail(UUID.randomUUID() + "@current.example.com");
        repository.saveAndFlush(user);
        mvc.perform(post(ENDPOINT).cookie(cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + UUID.randomUUID() + "\",\"email\":\"attacker@example.com\"}"))
                .andExpect(status().isAccepted());
        ArgumentCaptor<EmailVerificationRequestedMessage> captor = ArgumentCaptor.forClass(
                EmailVerificationRequestedMessage.class);
        verify(publisher).publish(captor.capture());
        assertEquals(user.getId(), captor.getValue().getUserId());
        assertEquals(user.getEmail(), captor.getValue().getRecipientEmail());
    }

    @Test
    void dbBanOverridesActiveClaimInJwt() throws Exception {
        user.setStatus(UserStatus.BANNED);
        repository.saveAndFlush(user);
        mvc.perform(post(ENDPOINT).cookie(cookie()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1003));
        verifyNoInteractions(store, publisher);
        assertEquals(UserStatus.BANNED, repository.findById(user.getId()).orElseThrow().getStatus());
        assertFalse(repository.findById(user.getId()).orElseThrow().isEmailVerified());
    }

    @Test
    void verifiedEmailReturns409EvenWhenJwtSaysUnverified() throws Exception {
        user.setEmailVerified(true);
        repository.saveAndFlush(user);
        mvc.perform(post(ENDPOINT).cookie(cookie()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1020));
        verifyNoInteractions(store, publisher);
    }

    @Test
    void deletedUserReturns404() throws Exception {
        repository.deleteById(user.getId());
        mvc.perform(post(ENDPOINT).cookie(cookie()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1004));
        verifyNoInteractions(store, publisher);
    }

    @Test
    void cooldownReturns429WithoutRetryAfterOrCountdownDto() throws Exception {
        when(store.hasCooldown(user.getId())).thenReturn(true);
        mvc.perform(post(ENDPOINT).cookie(cookie()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(1021))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        verifyNoInteractions(publisher);
        verify(store, never()).save(any(), anyString());
    }

    @Test
    void redisOutageReturns503WithoutPublishing() throws Exception {
        when(store.hasCooldown(user.getId())).thenThrow(new RedisConnectionFailureException("test failure"));
        mvc.perform(post(ENDPOINT).cookie(cookie()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1022));
        verifyNoInteractions(publisher);
    }

    @Test
    void kafkaUnknownOutcomeReturns503AndKeepsOtp() throws Exception {
        doThrow(new EmailVerificationPublishException(true, new IllegalStateException("timeout")))
                .when(publisher).publish(any());
        mvc.perform(post(ENDPOINT).cookie(cookie()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1022));
        verify(store, never()).deleteIfCurrent(any(), anyString());
        assertFalse(repository.findById(user.getId()).orElseThrow().isEmailVerified());
    }

    private void authenticate(String subject) {
        Jwt.Builder jwt = Jwt.withTokenValue("otp-access-token").header("alg", "HS512")
                .claim("scope", "USER").claim("status", "ACTIVE")
                .claim("emailVerified", false).claim("email", "stale@example.com");
        if (subject != null) {
            jwt.subject(subject);
        }
        when(decoder.decode("otp-access-token")).thenReturn(jwt.build());
    }

    private Cookie cookie() {
        return new Cookie("access_token", "otp-access-token");
    }
}
