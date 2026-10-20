package com.reallive.notificationservice.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.notificationservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.notificationservice.common.exception.AppException;
import com.reallive.notificationservice.common.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityFilterChainTest.TestController.class,
        properties = "auth.cookie.access-token.name=access_token")
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

    @Test
    void unauthenticatedGetReturnsStandard401() throws Exception {
        mvc.perform(get("/test/identity")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("Unauthorized"));
    }

    @Test
    void unauthenticatedPostReturns401() throws Exception {
        mvc.perform(post("/test/identity")).andExpect(status().isUnauthorized());
    }

    @Test
    void authorizationHeaderWithoutAccessCookieReturns401() throws Exception {
        mvc.perform(get("/test/identity").header("Authorization", "Bearer " + token("USER")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validAccessCookieAuthenticatesTheSubject() throws Exception {
        mvc.perform(get("/test/identity").cookie(new Cookie("access_token", token("USER"))))
                .andExpect(status().isOk()).andExpect(content().string("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void authenticatedPostDoesNotRequireACsrfToken() throws Exception {
        mvc.perform(post("/test/identity").cookie(new Cookie("access_token", token("USER"))))
                .andExpect(status().isOk()).andExpect(content().string("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void insufficientAuthorityReturnsStandard403() throws Exception {
        mvc.perform(get("/test/admin").cookie(new Cookie("access_token", token("USER"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value("Access Denied"));
    }

    @Test
    void matchingAuthorityAllowsTheProtectedMethod() throws Exception {
        mvc.perform(get("/test/admin").cookie(new Cookie("access_token", token("USER ADMIN"))))
                .andExpect(status().isOk()).andExpect(content().string("admin"));
    }

    @Test
    void malformedAccessTokenReturns401() throws Exception {
        mvc.perform(get("/test/identity").cookie(new Cookie("access_token", "malformed-token")))
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
        mvc.perform(get("/test/identity").cookie(new Cookie("access_token", token(claims.build()))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void unavailableAuthoritiesReturn503FromSecurityWithoutClearingCookies() throws Exception {
        String access = token("ROLE_USER");
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenThrow(new AppException(
                        ErrorCode.AUTH_STATE_UNAVAILABLE));
        mvc.perform(get("/notification/test/identity").contextPath("/notification")
                        .cookie(new Cookie("access_token", access)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(MockMvcResultMatchers.header().doesNotExist("Set-Cookie"));
    }


    @Test
    void publicEndpointWithoutCookieRemainsAnonymous() throws Exception {
        mvc.perform(post("/notification/api/webhooks/sendgrid").contextPath("/notification"))
                .andExpect(status().isOk()).andExpect(content().string("anonymous"));
        Mockito.verifyNoInteractions(authAuthoritiesReader);
    }

    @Test
    void publicEndpointWithAccessCookieReceivesJwtIdentity() throws Exception {
        mvc.perform(post("/notification/api/webhooks/sendgrid").contextPath("/notification")
                        .cookie(new Cookie("access_token", token("ROLE_USER"))))
                .andExpect(status().isOk()).andExpect(content().string("00000000-0000-0000-0000-000000000001"));
        Mockito.verify(authAuthoritiesReader).read(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void publicEndpointRejectsMalformedAccessCookie() throws Exception {
        mvc.perform(post("/notification/api/webhooks/sendgrid").contextPath("/notification")
                        .cookie(new Cookie("access_token", "malformed-token")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
        Mockito.verifyNoInteractions(authAuthoritiesReader);
    }

    @Test
    void publicEndpointWithUnavailableAuthoritiesReturns503() throws Exception {
        String access = token("ROLE_USER");
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenThrow(new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE));
        mvc.perform(post("/notification/api/webhooks/sendgrid").contextPath("/notification")
                        .cookie(new Cookie("access_token", access)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(MockMvcResultMatchers.header().doesNotExist("Set-Cookie"));
    }

    private String token(String scope) throws Exception {
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenReturn(Arrays.stream(scope.split(" ")).filter(value -> !value.isBlank())
                        .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList());
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject("00000000-0000-0000-0000-000000000001").issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900))).claim("scope", scope).build();
        return token(claims);
    }

    private String token(JWTClaimsSet claims) throws Exception {
        var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signed.sign(new MACSigner("common-security-test-secret-".repeat(4)));
        return signed.serialize();
    }

    @RestController
    public static class TestController {

        @PostMapping("/api/webhooks/sendgrid")
        public String publicIdentity(@AuthenticationPrincipal Jwt jwt) {
            return jwt == null ? "anonymous" : jwt.getSubject();
        }

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
