package com.reallive.channelservice.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.channelservice.common.exception.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityFilterChainTest.TestController.class,
        properties = {"ACCESS_TOKEN_COOKIE_NAME=channel_access", "CHANNEL_DB_PASSWORD=test-placeholder-only"})
@ActiveProfiles("dev")
@Import({SecurityConfig.class, CustomJwtDecoder.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class, SecurityFilterChainTest.TestController.class})
class SecurityFilterChainTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Environment environment;

    @Test
    void developmentProfileLoadsChannelContextPathAndConfiguredCookieName() {
        assertEquals("/channel", environment.getProperty("server.servlet.context-path"));
        assertEquals("channel_access", environment.getProperty("auth.cookie.access-token.name"));
    }

    @Test
    void unauthenticatedGetReturnsStandard401() throws Exception {
        mvc.perform(get("/channel/test/identity").contextPath("/channel"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("Unauthorized"));
    }

    @Test
    void unauthenticatedPostReturns401() throws Exception {
        mvc.perform(post("/channel/test/identity").contextPath("/channel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authorizationHeaderWithoutAccessCookieReturns401() throws Exception {
        mvc.perform(get("/channel/test/identity").contextPath("/channel")
                        .header("Authorization", "Bearer " + token("USER")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unconfiguredCookieNameCannotAuthenticate() throws Exception {
        mvc.perform(get("/channel/test/identity").contextPath("/channel")
                        .cookie(new Cookie("access_token", token("USER"))))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingOrBlankConfiguredCookieReturns401(String cookieValue) throws Exception {
        mvc.perform(get("/channel/test/identity").contextPath("/channel")
                        .cookie(new Cookie("channel_access", cookieValue)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }

    @Test
    void configuredCookieAuthenticatesEvenWithInvalidAuthorizationHeader() throws Exception {
        mvc.perform(get("/channel/test/identity").contextPath("/channel")
                        .header("Authorization", "Bearer malformed-token")
                        .cookie(new Cookie("channel_access", token("ROLE_USER"))))
                .andExpect(status().isOk()).andExpect(content().string("test-user"));
    }

    @Test
    void validConfiguredAccessCookieAuthenticatesTheSubject() throws Exception {
        mvc.perform(get("/channel/test/identity").contextPath("/channel")
                        .cookie(new Cookie("channel_access", token("USER"))))
                .andExpect(status().isOk()).andExpect(content().string("test-user"));
    }

    @Test
    void authenticatedPostUsesExistingGatewayCsrfPolicy() throws Exception {
        mvc.perform(post("/channel/test/identity").contextPath("/channel")
                        .cookie(new Cookie("channel_access", token("USER"))))
                .andExpect(status().isOk()).andExpect(content().string("test-user"));
    }

    @Test
    void insufficientAuthorityReturnsStandard403() throws Exception {
        mvc.perform(get("/channel/test/admin").contextPath("/channel")
                        .cookie(new Cookie("channel_access", token("USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value("Access Denied"));
    }

    @Test
    void matchingAuthorityAllowsTheProtectedMethod() throws Exception {
        mvc.perform(get("/channel/test/admin").contextPath("/channel")
                        .cookie(new Cookie("channel_access", token("USER ADMIN"))))
                .andExpect(status().isOk()).andExpect(content().string("admin"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ROLE_USER", "STREAMER"})
    void missingStreamerRoleReturns403(String scope) throws Exception {
        mvc.perform(get("/channel/test/streamer").contextPath("/channel")
                        .cookie(new Cookie("channel_access", token(scope))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value("Access Denied"));
    }

    @Test
    void streamerRoleAllowsTheProtectedMethod() throws Exception {
        mvc.perform(get("/channel/test/streamer").contextPath("/channel")
                        .cookie(new Cookie("channel_access", token("ROLE_USER ROLE_STREAMER"))))
                .andExpect(status().isOk()).andExpect(content().string("streamer"));
    }

    @Test
    void databaseFailureAfterAuthenticationUsesCommon503Envelope() throws Exception {
        var response = mvc.perform(get("/channel/test/database-failure").contextPath("/channel")
                        .cookie(new Cookie("channel_access", token("ROLE_USER"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value(1003))
                .andExpect(jsonPath("$.message").value("Channel data unavailable"))
                .andExpect(jsonPath("$.result").value(false))
                .andReturn().getResponse();

        assertFalse(response.getContentAsString().contains("private SQL detail"));
    }

    @Test
    void malformedAccessTokenReturns401() throws Exception {
        mvc.perform(get("/channel/test/identity").contextPath("/channel")
                        .cookie(new Cookie("channel_access", "malformed-token")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
    }

    @ParameterizedTest
    @ValueSource(strings = {"iat", "exp"})
    void missingRequiredTimestampReturns401(String missingClaim) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject("test-user");
        if (!"iat".equals(missingClaim)) {
            claims.issueTime(Date.from(now));
        }
        if (!"exp".equals(missingClaim)) {
            claims.expirationTime(Date.from(now.plusSeconds(900)));
        }

        mvc.perform(get("/channel/test/identity").contextPath("/channel")
                        .cookie(new Cookie("channel_access", token(claims.build()))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void expirationBeforeIssueTimeReturns401() throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject("test-user").issueTime(Date.from(now))
                .expirationTime(Date.from(now.minusSeconds(1))).build();

        mvc.perform(get("/channel/test/identity").contextPath("/channel")
                        .cookie(new Cookie("channel_access", token(claims))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
    }

    private String token(String scope) throws Exception {
        Instant now = Instant.now();
        return token(new JWTClaimsSet.Builder().subject("test-user").issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900))).claim("scope", scope).build());
    }

    private String token(JWTClaimsSet claims) throws Exception {
        var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signed.sign(new MACSigner("channel-filter-test-secret-".repeat(4)));
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

        @PreAuthorize("hasRole('STREAMER')")
        @GetMapping("/test/streamer")
        public String streamer() {
            return "streamer";
        }

        @GetMapping("/test/database-failure")
        public String databaseFailure() {
            throw new DataAccessResourceFailureException("private SQL detail");
        }
    }
}
