package com.reallive.paymentservice.common.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SecurityConfigTest {

    private final SecurityConfig security = new SecurityConfig(null, null, null);

    @Test
    void readsConfiguredCookieAndIgnoresAuthorizationHeader() {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer header-token");
        request.setCookies(new Cookie("other_cookie", "ignored"), new Cookie("custom_access", "cookie-token"));

        assertEquals("cookie-token", security.bearerTokenResolver("custom_access").resolve(request));
    }

    @Test
    void authorizationHeaderAloneCannotAuthenticate() {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer header-token");

        assertNull(security.bearerTokenResolver("access_token").resolve(request));
    }

    @Test
    void unrelatedCookiesCannotAuthenticate() {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "refresh-value"));

        assertNull(security.bearerTokenResolver("access_token").resolve(request));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void missingOrBlankAccessCookieCannotAuthenticate(String value) {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("access_token", value));

        assertNull(security.bearerTokenResolver("access_token").resolve(request));
    }

    @Test
    void readsAccessCookieOnEveryPaymentPathWithoutNotificationExceptions() {
        var request = new MockHttpServletRequest("POST", "/payment/api/webhooks/sendgrid");
        request.setContextPath("/payment");
        request.setCookies(new Cookie("access_token", "cookie-token"));

        assertEquals("cookie-token", security.bearerTokenResolver("access_token").resolve(request));
    }

    @Test
    void scopeAuthoritiesKeepTheirNamesAndPrincipalUsesSubject() {
        var jwt = Jwt.withTokenValue("token").header("alg", "HS512").subject("user-id")
                .claim("scope", "USER ADMIN").build();

        var authentication = security.jwtAuthenticationConverter().convert(jwt);

        assertEquals("user-id", authentication.getName());
        assertEquals(List.of("ADMIN", FactorGrantedAuthority.BEARER_AUTHORITY, "USER"),
                authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList());
    }

    @Test
    void tokenWithoutScopeOnlyHasTheFrameworkBearerFactor() {
        var jwt = Jwt.withTokenValue("token").header("alg", "HS512").subject("user-id").build();

        assertEquals(List.of(FactorGrantedAuthority.BEARER_AUTHORITY),
                security.jwtAuthenticationConverter().convert(jwt).getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).toList());
    }
}
