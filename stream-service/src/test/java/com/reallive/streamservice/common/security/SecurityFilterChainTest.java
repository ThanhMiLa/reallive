package com.reallive.streamservice.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityFilterChainTest.TestController.class,
        properties = {"ACCESS_TOKEN_COOKIE_NAME=stream_access", "STREAM_DB_PASSWORD=test-placeholder-only"})
@ActiveProfiles("dev")
@Import({SecurityConfig.class, CustomJwtDecoder.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, SecurityFilterChainTest.TestController.class})
class SecurityFilterChainTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @MockitoBean
    private RedisAuthoritiesReader authoritiesReader;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Environment environment;

    @BeforeEach
    void setUpAuthorities() {
        when(authoritiesReader.read(any())).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void developmentProfileLoadsServiceIdentityAndConfiguredCookie() {
        assertEquals("stream-service", environment.getProperty("spring.application.name"));
        assertEquals("/stream", environment.getProperty("server.servlet.context-path"));
        assertEquals("stream_access", environment.getProperty("auth.cookie.access-token.name"));
    }

    @Test
    void missingAuthenticationReturnsTheStandard401Envelope() throws Exception {
        mvc.perform(get("/stream/test/identity").contextPath("/stream"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("Unauthorized"));
        verifyNoInteractions(authoritiesReader);
    }

    @Test
    void authorizationHeaderCannotReplaceTheAccessCookie() throws Exception {
        mvc.perform(get("/stream/test/identity").contextPath("/stream")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(authoritiesReader);
    }

    @Test
    void unconfiguredCookieCannotAuthenticate() throws Exception {
        mvc.perform(get("/stream/test/identity").contextPath("/stream")
                        .cookie(new Cookie("access_token", token())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(authoritiesReader);
    }

    @Test
    void configuredAccessCookieAuthenticatesTheSubject() throws Exception {
        mvc.perform(get("/stream/test/identity").contextPath("/stream")
                        .cookie(new Cookie("stream_access", token())))
                .andExpect(status().isOk())
                .andExpect(content().string(USER_ID.toString()));
        verify(authoritiesReader).read(USER_ID);
    }

    @Test
    void authenticatedPostUsesTheGatewayCsrfPolicy() throws Exception {
        mvc.perform(post("/stream/test/identity").contextPath("/stream")
                        .cookie(new Cookie("stream_access", token())))
                .andExpect(status().isOk())
                .andExpect(content().string(USER_ID.toString()));
    }

    @Test
    void missingAuthenticationOnPostReturns401() throws Exception {
        mvc.perform(post("/stream/test/identity").contextPath("/stream"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void changedRedisPermissionsApplyToTheSameTokenAndIgnoreScope() throws Exception {
        String accessToken = token();
        mvc.perform(get("/stream/test/streamer").contextPath("/stream")
                        .cookie(new Cookie("stream_access", accessToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value("Access Denied"));

        when(authoritiesReader.read(USER_ID)).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_STREAMER")));
        mvc.perform(get("/stream/test/streamer").contextPath("/stream")
                        .cookie(new Cookie("stream_access", accessToken)))
                .andExpect(status().isOk())
                .andExpect(content().string("streamer"));
    }

    @Test
    void emptyAuthoritiesStillAllowAuthenticatedIdentity() throws Exception {
        when(authoritiesReader.read(USER_ID)).thenReturn(List.of());
        mvc.perform(get("/stream/test/identity").contextPath("/stream")
                        .cookie(new Cookie("stream_access", token())))
                .andExpect(status().isOk())
                .andExpect(content().string(USER_ID.toString()));
    }

    @Test
    void malformedTokenIsRejectedBeforeLoadingAuthorities() throws Exception {
        mvc.perform(get("/stream/test/identity").contextPath("/stream")
                        .cookie(new Cookie("stream_access", "malformed-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
        verifyNoInteractions(authoritiesReader);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"invalid-user-id"})
    void invalidSubjectIsRejectedBeforeLoadingAuthorities(String subject) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject(subject)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900))).build();

        mvc.perform(get("/stream/test/identity").contextPath("/stream")
                        .cookie(new Cookie("stream_access", signedToken(claims))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
        verifyNoInteractions(authoritiesReader);
    }

    @ParameterizedTest
    @ValueSource(strings = {"iat", "exp"})
    void missingTimestampsAreRejected(String missingClaim) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject(USER_ID.toString());
        if (!"iat".equals(missingClaim)) {
            claims.issueTime(Date.from(now));
        }
        if (!"exp".equals(missingClaim)) {
            claims.expirationTime(Date.from(now.plusSeconds(900)));
        }

        mvc.perform(get("/stream/test/identity").contextPath("/stream")
                        .cookie(new Cookie("stream_access", signedToken(claims.build()))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(authoritiesReader);
    }

    @Test
    void unavailableAuthStateReturns503AndRetainsCookies() throws Exception {
        when(authoritiesReader.read(USER_ID)).thenThrow(new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE));

        mvc.perform(get("/stream/test/identity").contextPath("/stream")
                        .cookie(new Cookie("stream_access", token())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value(1005))
                .andExpect(jsonPath("$.message").value("Authentication state temporarily unavailable"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    private String token() throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject(USER_ID.toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900)))
                .claim("scope", "ROLE_STREAMER ROLE_ADMIN").build();
        return signedToken(claims);
    }

    private String signedToken(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        jwt.sign(new MACSigner(new byte[64]));
        return jwt.serialize();
    }

    @RestController
    public static class TestController {

        @GetMapping("/test/identity")
        public String identity(@AuthenticationPrincipal Jwt jwt) {
            return jwt.getSubject();
        }

        @PostMapping("/test/identity")
        public String updateIdentity(@AuthenticationPrincipal Jwt jwt) {
            return jwt.getSubject();
        }

        @PreAuthorize("hasRole('STREAMER')")
        @GetMapping("/test/streamer")
        public String streamer() {
            return "streamer";
        }
    }
}
