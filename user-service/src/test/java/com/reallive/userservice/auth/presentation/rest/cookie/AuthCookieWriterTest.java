package com.reallive.userservice.auth.presentation.rest.cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import com.reallive.userservice.auth.application.dto.response.TokenPair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthCookieWriterTest {

    private AuthCookieWriter cookieWriter;

    @BeforeEach
    void setUp() {
        cookieWriter = new AuthCookieWriter();
        ReflectionTestUtils.setField(cookieWriter, "accessTokenName", "session_access");
        ReflectionTestUtils.setField(cookieWriter, "accessTokenMaxAge", 1234L);
        ReflectionTestUtils.setField(cookieWriter, "accessTokenSecure", false);
        ReflectionTestUtils.setField(cookieWriter, "accessTokenHttpOnly", false);
        ReflectionTestUtils.setField(cookieWriter, "accessTokenSameSite", "Strict");
        ReflectionTestUtils.setField(cookieWriter, "accessTokenPath", "/api");
        ReflectionTestUtils.setField(cookieWriter, "refreshTokenName", "session_refresh");
        ReflectionTestUtils.setField(cookieWriter, "refreshTokenMaxAge", 5678L);
        ReflectionTestUtils.setField(cookieWriter, "refreshTokenSecure", true);
        ReflectionTestUtils.setField(cookieWriter, "refreshTokenHttpOnly", true);
        ReflectionTestUtils.setField(cookieWriter, "refreshTokenSameSite", "None");
        ReflectionTestUtils.setField(cookieWriter, "refreshTokenPath", "/api/auth");
    }

    @Test
    void buildsEachCookieFromItsOwnConfigurationWithoutReadingJwtClaims() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieWriter.initialize();
        cookieWriter.write(new TokenPair("access-token-value", "refresh-token-value"), response);

        assertThat(response.getCookie("session_access").getValue()).isEqualTo("access-token-value");
        assertThat(response.getCookie("session_access").getMaxAge()).isEqualTo(1234);
        assertThat(response.getCookie("session_access").getSecure()).isFalse();
        assertThat(response.getCookie("session_access").isHttpOnly()).isFalse();
        assertThat(response.getCookie("session_access").getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(response.getCookie("session_access").getPath()).isEqualTo("/api");
        assertThat(response.getCookie("session_refresh").getValue()).isEqualTo("refresh-token-value");
        assertThat(response.getCookie("session_refresh").getMaxAge()).isEqualTo(5678);
        assertThat(response.getCookie("session_refresh").getSecure()).isTrue();
        assertThat(response.getCookie("session_refresh").isHttpOnly()).isTrue();
        assertThat(response.getCookie("session_refresh").getAttribute("SameSite")).isEqualTo("None");
        assertThat(response.getCookie("session_refresh").getPath()).isEqualTo("/api/auth");
    }

    @Test
    void configuredZeroMaxAgeClearsCookie() {
        ReflectionTestUtils.setField(cookieWriter, "accessTokenMaxAge", 0L);
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieWriter.initialize();
        cookieWriter.write(new TokenPair("access-token-value", "refresh-token-value"), response);
        assertThat(response.getCookie("session_access").getMaxAge()).isZero();
        assertThat(response.getCookie("session_refresh").getMaxAge()).isEqualTo(5678);
    }

    @Test
    void rejectsInsecureSameSiteNoneOnEitherCookie() {
        ReflectionTestUtils.setField(cookieWriter, "accessTokenSameSite", "None");
        assertThatThrownBy(cookieWriter::initialize).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auth.cookie.access-token");
        ReflectionTestUtils.setField(cookieWriter, "accessTokenSameSite", "Strict");
        ReflectionTestUtils.setField(cookieWriter, "refreshTokenSecure", false);
        assertThatThrownBy(cookieWriter::initialize).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auth.cookie.refresh-token");
    }

    @Test
    void rejectsNegativeMaxAgeAndInvalidCookiePath() {
        ReflectionTestUtils.setField(cookieWriter, "accessTokenMaxAge", -1L);
        assertThatThrownBy(cookieWriter::initialize).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-age");
        ReflectionTestUtils.setField(cookieWriter, "accessTokenMaxAge", 1234L);
        ReflectionTestUtils.setField(cookieWriter, "refreshTokenPath", "api/auth");
        assertThatThrownBy(cookieWriter::initialize).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }
}
