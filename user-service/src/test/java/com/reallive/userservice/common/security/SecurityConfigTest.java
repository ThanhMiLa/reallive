package com.reallive.userservice.common.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SecurityConfigTest {

    private final SecurityConfig security = new SecurityConfig(null, null, null);

    @Test
    void readsConfiguredCookieAndIgnoresAuthorizationHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer untrusted-header");
        request.setCookies(new Cookie("custom_access", "trusted-cookie"));

        assertEquals("trusted-cookie", security.bearerTokenResolver("custom_access").resolve(request));
    }

    @Test
    void headerAloneCannotAuthenticate() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer untrusted-header");

        assertNull(security.bearerTokenResolver("access_token").resolve(request));
    }
}
