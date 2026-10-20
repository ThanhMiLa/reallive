package com.reallive.userservice.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.userservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PublicEndpointSecurityTest.TestController.class,
        properties = "auth.cookie.access-token.name=custom_access")
@ActiveProfiles("test")
@Import({SecurityConfig.class, CustomJwtDecoder.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, PublicEndpointSecurityTest.TestController.class})
class PublicEndpointSecurityTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String PATH = "/user/api/auth/login";

    @MockitoBean
    private RedisAuthoritiesReader authoritiesReader;

    @Autowired
    private MockMvc mvc;

    @Test
    void publicEndpointWithoutCookieRemainsAnonymous() throws Exception {
        mvc.perform(post(PATH).contextPath("/user"))
                .andExpect(status().isOk()).andExpect(content().string("anonymous"));
        verifyNoInteractions(authoritiesReader);
    }

    @Test
    void publicEndpointWithAccessCookieReceivesJwtIdentity() throws Exception {
        when(authoritiesReader.read(USER_ID)).thenReturn(List.of());

        mvc.perform(post(PATH).contextPath("/user").cookie(new Cookie("custom_access", token())))
                .andExpect(status().isOk()).andExpect(content().string(USER_ID.toString()));
        verify(authoritiesReader).read(USER_ID);
    }

    @Test
    void publicEndpointRejectsMalformedAccessCookie() throws Exception {
        mvc.perform(post(PATH).contextPath("/user").cookie(new Cookie("custom_access", "malformed-token")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
        verifyNoInteractions(authoritiesReader);
    }

    @Test
    void publicEndpointWithUnavailableAuthoritiesReturns503() throws Exception {
        when(authoritiesReader.read(USER_ID)).thenThrow(new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE));

        mvc.perform(post(PATH).contextPath("/user").cookie(new Cookie("custom_access", token())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    private String token() throws Exception {
        Instant now = Instant.now();
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), new JWTClaimsSet.Builder()
                .subject(USER_ID.toString()).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900))).build());
        token.sign(new MACSigner("user-public-endpoint-test-secret-".repeat(4)));
        return token.serialize();
    }

    @RestController
    public static class TestController {

        @PostMapping("/api/auth/login")
        public String publicIdentity(@AuthenticationPrincipal Jwt jwt) {
            return jwt == null ? "anonymous" : jwt.getSubject();
        }
    }
}
