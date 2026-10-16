package com.reallive.userservice.auth.controller;

import com.nimbusds.jwt.SignedJWT;
import com.reallive.userservice.auth.adapter.redis.AuthStateStore;
import com.reallive.userservice.auth.entity.RoleEntity;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.client.wallet.PaymentHttpTestServer;
import com.reallive.userservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.repository.ProfileRepository;
import feign.FeignException;
import feign.RetryableException;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.cloud.openfeign.client.config.payment-service.connectTimeout=300",
        "spring.cloud.openfeign.client.config.payment-service.readTimeout=300"
})
@ActiveProfiles("test")
class RegistrationWalletIntegrationTest {
    @MockitoBean
    private RedisAuthoritiesReader authAuthoritiesReader;

    @BeforeEach
    void setUpAuthAuthorities() {
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @MockitoBean
    private AuthStateStore authStateStore;

    private static final String SCHEMA = "register_wallet_" + UUID.randomUUID().toString().replace("-", "");
    private static final PaymentHttpTestServer PAYMENT = new PaymentHttpTestServer();
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository users;
    @Autowired
    private ProfileRepository profiles;
    @Autowired
    private RoleRepository roles;
    @Autowired
    private ObjectMapper mapper;
    private MockMvc mvc;
    private String username;
    private String email;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.service.payment", PAYMENT::baseUrl);
        registry.add("spring.datasource.url", () -> System.getProperty("registration.test.database-url",
                "jdbc:h2:mem:register_wallet;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username", () -> System.getProperty("registration.test.database-username", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("registration.test.database-password", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "\"" + SCHEMA + "\"");
    }

    @BeforeEach
    void setUp() {
        PAYMENT.reset();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        roles.findByName("USER").orElseGet(() -> roles.saveAndFlush(RoleEntity.builder().name("USER").build()));
        username = "wallet-" + UUID.randomUUID();
        email = UUID.randomUUID() + "@example.com";
    }

    @AfterEach
    void releaseDelayedResponse() {
        PAYMENT.releaseResponse();
    }

    @AfterAll
    static void cleanUp(@Autowired JdbcTemplate jdbc) {
        PAYMENT.close();
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void registrationWithoutCookiePersistsUserAndProfileAndCallsPaymentBeforeIssuingAuthCookies() throws Exception {
        MvcResult result = register("password123").andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(cookie().exists("access_token"))
                .andExpect(cookie().exists("refresh_token"))
                .andExpect(cookie().httpOnly("access_token", true)).andReturn();
        var user = users.findByUsername(username).orElseThrow();
        var request = PAYMENT.takeRequest();

        assertTrue(PAYMENT.hasWallet(user.getId()));
        assertEquals("/payment/internal/api/wallets/create/" + user.getId(), request.path());
        assertNull(request.header("Cookie"));
        assertNull(request.header("Authorization"));
        assertEquals(1, PAYMENT.callCount());
        assertEquals(email, user.getEmail());
        assertNotEquals("password123", user.getPasswordHash());
        assertEquals(username, profiles.findByUserId(user.getId()).orElseThrow().getDisplayName());
        var response = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(response.path("result").path("accessToken").isNull());
        assertTrue(response.path("result").path("refreshToken").isNull());
        assertEquals(user.getId().toString(), SignedJWT.parse(result.getResponse().getCookie("access_token").getValue())
                .getJWTClaimsSet().getSubject());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 409, 500, 503})
    void paymentHttpFailureRollsBackUserAndProfile(int status) {
        PAYMENT.setErrorStatus(status);
        long usersBefore = users.count();
        long profilesBefore = profiles.count();

        ServletException failure = assertThrows(ServletException.class, () -> register("password123"));

        assertInstanceOf(FeignException.class, failure.getCause());
        assertEquals(status, ((FeignException) failure.getCause()).status());
        assertFalse(users.existsByUsername(username));
        assertEquals(usersBefore, users.count());
        assertEquals(profilesBefore, profiles.count());
        assertEquals(1, PAYMENT.callCount());
    }

    @Test
    void lostResponseRollsBackUserButLeavesRemoteWalletAndAllowsImmediateRegistrationRetry() throws Exception {
        PAYMENT.setMode(PaymentHttpTestServer.Mode.LOST_RESPONSE);
        long profilesBefore = profiles.count();

        ServletException failure = assertThrows(ServletException.class, () -> register("password123"));

        assertInstanceOf(RetryableException.class, failure.getCause());
        String path = PAYMENT.takeRequest().path();
        UUID rolledBackUserId = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
        assertTrue(PAYMENT.hasWallet(rolledBackUserId));
        assertFalse(users.existsById(rolledBackUserId));
        assertFalse(users.existsByUsername(username));
        assertEquals(profilesBefore, profiles.count());

        PAYMENT.releaseResponse();
        PAYMENT.setMode(PaymentHttpTestServer.Mode.SUCCESS);
        register("password123").andExpect(status().isOk());
        UUID retriedUserId = users.findByUsername(username).orElseThrow().getId();
        assertNotEquals(rolledBackUserId, retriedUserId);
        assertTrue(PAYMENT.hasWallet(retriedUserId));
        assertTrue(PAYMENT.hasWallet(rolledBackUserId));
        assertEquals(2, PAYMENT.callCount());
    }

    @Test
    void malformedPaymentReplyRollsBackBothLocalRecords() {
        PAYMENT.setMode(PaymentHttpTestServer.Mode.INVALID_JSON);
        long profilesBefore = profiles.count();

        ServletException failure = assertThrows(ServletException.class, () -> register("password123"));

        assertInstanceOf(FeignException.class, failure.getCause());
        assertFalse(users.existsByUsername(username));
        assertEquals(profilesBefore, profiles.count());
    }

    @Test
    void emptyPaymentReplyCurrentlyFailsAndRollsBackBothLocalRecords() {
        PAYMENT.setMode(PaymentHttpTestServer.Mode.EMPTY_BODY);
        long profilesBefore = profiles.count();

        ServletException failure = assertThrows(ServletException.class, () -> register("password123"));

        assertInstanceOf(NullPointerException.class, failure.getCause());
        assertFalse(users.existsByUsername(username));
        assertEquals(profilesBefore, profiles.count());
    }

    @Test
    void duplicateUsernameDoesNotCallPaymentAgain() throws Exception {
        register("password123").andExpect(status().isOk());
        email = UUID.randomUUID() + "@example.com";

        register("password123").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.USERNAME_ALREADY_EXISTS.getCode()));

        assertEquals(1, PAYMENT.callCount());
    }

    @Test
    void duplicateEmailDoesNotCallPaymentAgain() throws Exception {
        register("password123").andExpect(status().isOk());
        username = "wallet-" + UUID.randomUUID();

        register("password123").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_ALREADY_EXISTS.getCode()));

        assertEquals(1, PAYMENT.callCount());
    }

    @Test
    void passwordMismatchDoesNotSaveUserOrCallPayment() throws Exception {
        register("different-password").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PASSWORD_NOT_MATCH.getCode()));
        assertFalse(users.existsByUsername(username));
        assertEquals(0, PAYMENT.callCount());
    }

    @Test
    void invalidInputDoesNotReachPayment() throws Exception {
        username = "a";
        register("password123").andExpect(status().isBadRequest());
        assertEquals(0, PAYMENT.callCount());
    }

    private ResultActions register(String confirmation) throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", username, "email", email,
                        "password", "password123", "confirmPassword", confirmation))));
    }
}
