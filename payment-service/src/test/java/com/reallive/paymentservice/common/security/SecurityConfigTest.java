package com.reallive.paymentservice.common.security;

import com.reallive.paymentservice.common.adapter.redis.RedisAuthoritiesReader;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurityConfigTest {

    private final RedisAuthoritiesReader reader = mock(RedisAuthoritiesReader.class);
    private final SecurityConfig security = new SecurityConfig(null, null, null, reader);

    @Test
    void readsConfiguredCookieAndIgnoresAuthorizationHeader() {
        var request = new MockHttpServletRequest("GET", "/test/protected");
        request.addHeader("Authorization", "Bearer untrusted-header");
        request.setCookies(new Cookie("custom_access", "trusted-cookie"));
        assertEquals("trusted-cookie", security.bearerTokenResolver("custom_access").resolve(request));
    }

    @Test
    void headerAloneCannotAuthenticate() {
        var request = new MockHttpServletRequest("GET", "/test/protected");
        request.addHeader("Authorization", "Bearer untrusted-header");
        assertNull(security.bearerTokenResolver("access_token").resolve(request));
    }

    @Test
    void blankAndUnrelatedCookiesCannotAuthenticate() {
        var request = new MockHttpServletRequest("GET", "/test/protected");
        request.setCookies(new Cookie("access_token", " "), new Cookie("refresh_token", "refresh"));
        assertNull(security.bearerTokenResolver("access_token").resolve(request));
    }

    @Test
    void usesCurrentRedisAuthoritiesAndIgnoresOldScope() {
        UUID userId = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("old-token").header("alg", "HS512").subject(userId.toString())
                .claim("scope", "ROLE_ADMIN").build();
        when(reader.read(userId)).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
        var authentication = security.jwtAuthenticationConverter().convert(jwt);
        assertEquals(userId.toString(), authentication.getName());
        assertSame(jwt, authentication.getPrincipal());
        assertTrue(authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_USER")));
        assertFalse(authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN")));
        when(reader.read(userId)).thenReturn(List.of(new SimpleGrantedAuthority("CHANNEL_UPDATE")));
        var updated = security.jwtAuthenticationConverter().convert(jwt);
        assertTrue(updated.getAuthorities().contains(new SimpleGrantedAuthority("CHANNEL_UPDATE")));
        assertFalse(updated.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void tokenWithoutScopeLoadsAuthoritiesFromRedis() {
        UUID userId = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("token").header("alg", "HS512").subject(userId.toString()).build();
        when(reader.read(userId)).thenReturn(List.of());
        assertNotNull(security.jwtAuthenticationConverter().convert(jwt));
        verify(reader).read(userId);
    }
}
