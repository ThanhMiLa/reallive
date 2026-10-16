package com.reallive.paymentservice.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.paymentservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityFilterChainTest.TestController.class,
        properties = {"ACCESS_TOKEN_COOKIE_NAME=payment_access", "PAYMENT_DB_PASSWORD=test-placeholder-only"})
@ActiveProfiles("dev")
@Import({SecurityConfig.class, CustomJwtDecoder.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityFilterChainTest.TestController.class})
class SecurityFilterChainTest {
    @MockitoBean
    private RedisAuthoritiesReader authAuthoritiesReader;

    @BeforeEach
    void setUpAuthAuthorities() {
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Environment environment;

    @Test
    void developmentProfileLoadsPaymentContextPathAndConfiguredCookieName() {
        assertEquals("/payment", environment.getProperty("server.servlet.context-path"));
        assertEquals("payment_access", environment.getProperty("auth.cookie.access-token.name"));
    }

    @Test
    void unauthenticatedGetReturnsStandard401() throws Exception {
        mvc.perform(get("/payment/test/identity").contextPath("/payment"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("Unauthorized"));
    }

    @Test
    void unauthenticatedPostReturns401() throws Exception {
        mvc.perform(post("/payment/test/identity").contextPath("/payment"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authorizationHeaderWithoutAccessCookieReturns401() throws Exception {
        mvc.perform(get("/payment/test/identity").contextPath("/payment")
                        .header("Authorization", "Bearer " + token("USER")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unconfiguredCookieNameCannotAuthenticate() throws Exception {
        mvc.perform(get("/payment/test/identity").contextPath("/payment")
                        .cookie(new Cookie("access_token", token("USER"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validConfiguredAccessCookieAuthenticatesTheSubject() throws Exception {
        mvc.perform(get("/payment/test/identity").contextPath("/payment")
                        .cookie(new Cookie("payment_access", token("USER"))))
                .andExpect(status().isOk()).andExpect(content().string("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void authenticatedPostUsesExistingGatewayCsrfPolicy() throws Exception {
        mvc.perform(post("/payment/test/identity").contextPath("/payment")
                        .cookie(new Cookie("payment_access", token("USER"))))
                .andExpect(status().isOk()).andExpect(content().string("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void insufficientAuthorityReturnsStandard403() throws Exception {
        mvc.perform(get("/payment/test/admin").contextPath("/payment")
                        .cookie(new Cookie("payment_access", token("USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value("Access Denied"));
    }

    @Test
    void matchingAuthorityAllowsTheProtectedMethod() throws Exception {
        mvc.perform(get("/payment/test/admin").contextPath("/payment")
                        .cookie(new Cookie("payment_access", token("USER ADMIN"))))
                .andExpect(status().isOk()).andExpect(content().string("admin"));
    }

    @Test
    void malformedAccessTokenReturns401() throws Exception {
        mvc.perform(get("/payment/test/identity").contextPath("/payment")
                        .cookie(new Cookie("payment_access", "malformed-token")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
    }

    @ParameterizedTest
    @ValueSource(strings = {"iat", "exp"})
    void missingRequiredTimestampReturns401(String missingClaim) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject("00000000-0000-0000-0000-000000000001");
        if (!"iat".equals(missingClaim)) {
            claims.issueTime(Date.from(now));
        }
        if (!"exp".equals(missingClaim)) {
            claims.expirationTime(Date.from(now.plusSeconds(900)));
        }

        mvc.perform(get("/payment/test/identity").contextPath("/payment")
                        .cookie(new Cookie("payment_access", token(claims.build()))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void expirationBeforeIssueTimeReturns401() throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject("00000000-0000-0000-0000-000000000001").issueTime(Date.from(now))
                .expirationTime(Date.from(now.minusSeconds(1))).build();

        mvc.perform(get("/payment/test/identity").contextPath("/payment")
                        .cookie(new Cookie("payment_access", token(claims))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void unavailableAuthoritiesReturn503FromSecurityWithoutClearingCookies() throws Exception {
        String access = token("ROLE_USER");
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenThrow(new AppException(
                        ErrorCode.AUTH_STATE_UNAVAILABLE));
        mvc.perform(get("/payment/test/identity").contextPath("/payment")
                        .cookie(new Cookie("payment_access", access)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(MockMvcResultMatchers.header().doesNotExist("Set-Cookie"));
    }

    private String token(String scope) throws Exception {
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenReturn(Arrays.stream(scope.split(" ")).filter(value -> !value.isBlank())
                        .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList());
        Instant now = Instant.now();
        return token(new JWTClaimsSet.Builder().subject("00000000-0000-0000-0000-000000000001").issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900))).claim("scope", scope).build());
    }

    private String token(JWTClaimsSet claims) throws Exception {
        var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signed.sign(new MACSigner("payment-filter-test-secret-".repeat(4)));
        return signed.serialize();
    }

    @TestComponent
    @RestController
    public static class TestController {

        @GetMapping("/test/identity")
        public String identity(Authentication authentication) {
            return authentication.getName();
        }

        @PostMapping("/test/identity")
        public String postIdentity(Authentication authentication) {
            return authentication.getName();
        }

        @PreAuthorize("hasAuthority('ADMIN')")
        @GetMapping("/test/admin")
        public String admin() {
            return "admin";
        }
    }
}
