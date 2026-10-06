package com.reallive.userservice.auth.presentation.rest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.reallive.userservice.auth.TestSigningKeys;
import com.reallive.userservice.common.infrastructure.security.SecurityConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LoginIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String PASSWORD = "correct password";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        registry.add("jwt.signer-key", TestSigningKeys::privateKey);
    }

    @BeforeEach
    void seedUser() {
        jdbc.update("""
                INSERT INTO users (id, username, email, password_hash, status, email_verified)
                VALUES (?, 'alice', 'alice@example.com', ?, 'ACTIVE', FALSE)
                """, USER_ID, passwordEncoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE name = 'USER'", USER_ID);
    }

    @Test
    void logsInByUsernameThroughRealCsrfFlowAndSetsOnlyHttpOnlyAuthCookies() throws Exception {
        Cookie csrf = csrfCookie();
        MvcResult login = mockMvc.perform(login(" ALICE ", PASSWORD, csrf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("userId").value(USER_ID.toString()))
                .andExpect(jsonPath("username").value("alice"))
                .andExpect(jsonPath("emailVerified").value(false))
                .andExpect(jsonPath("roles[0]").value("USER"))
                .andExpect(jsonPath("accessToken").doesNotExist())
                .andExpect(jsonPath("refreshToken").doesNotExist())
                .andExpect(jsonPath("tokens").doesNotExist())
                .andExpect(jsonPath("passwordHash").doesNotExist())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();

        Cookie access = login.getResponse().getCookie("access_token");
        Cookie refresh = login.getResponse().getCookie("refresh_token");
        assertThat(access).isNotNull();
        assertThat(refresh).isNotNull();
        assertThat(access.isHttpOnly()).isTrue();
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(access.getSecure()).isTrue();
        assertThat(refresh.getSecure()).isTrue();
        assertThat(access.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(refresh.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(access.getPath()).isEqualTo("/");
        assertThat(refresh.getPath()).isEqualTo("/api/auth");
        assertThat(access.getDomain()).isNull();
        assertThat(refresh.getDomain()).isNull();
        assertThat(access.getMaxAge()).isEqualTo(86400);
        assertThat(refresh.getMaxAge()).isEqualTo(86400);
        assertThat(login.getResponse().getCookie("JSESSIONID")).isNull();
        assertThat(login.getRequest().getSession(false)).isNull();
        assertThat(login.getResponse().getCookie("XSRF-TOKEN").getValue()).isNotEqualTo(csrf.getValue());

        SecretKeySpec secretKey = new SecretKeySpec(TestSigningKeys.privateKey().getBytes(StandardCharsets.UTF_8),
                "HmacSHA512");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS512).build();
        Jwt accessJwt = decoder.decode(access.getValue());
        Jwt refreshJwt = decoder.decode(refresh.getValue());
        assertThat(accessJwt.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(accessJwt.getHeaders().get("alg")).isEqualTo("HS512");
        assertThat(accessJwt.getClaimAsString("type")).isEqualTo("access");
        assertThat(refreshJwt.getClaimAsString("type")).isEqualTo("refresh");
        assertThat(accessJwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(refreshJwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(accessJwt.getExpiresAt().getEpochSecond() - accessJwt.getIssuedAt().getEpochSecond()).isEqualTo(900);
        assertThat(refreshJwt.getExpiresAt().getEpochSecond() - refreshJwt.getIssuedAt().getEpochSecond()).isEqualTo(2592000);
        assertThat(accessJwt.getId()).isNotEqualTo(refreshJwt.getId());
        assertThat(login.getResponse().getContentAsString()).doesNotContain(access.getValue(), refresh.getValue());
    }

    @Test
    void usernameLoginIsCaseInsensitive() throws Exception {
        mockMvc.perform(login(" ALICE ", PASSWORD, csrfCookie())).andExpect(status().isOk());
    }

    @Test
    void emailCannotBeUsedInsteadOfUsername() throws Exception {
        MvcResult denied = mockMvc.perform(login(" ALICE@EXAMPLE.COM ", PASSWORD, csrfCookie()))
                .andExpect(status().isUnauthorized()).andReturn();
        assertNoAuthCookies(denied);
    }

    @Test
    void oldIdentifierFieldDoesNotReplaceRequiredUsername() throws Exception {
        Cookie csrf = csrfCookie();
        MvcResult invalid = mockMvc.perform(post("/api/auth/login").cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"alice\",\"password\":\"correct password\"}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertNoAuthCookies(invalid);
    }

    @Test
    void wrongPasswordAndUnknownUserHaveIdenticalErrorsWithoutAuthCookies() throws Exception {
        Cookie csrf = csrfCookie();
        MvcResult wrongPassword = mockMvc.perform(login("alice", "wrong", csrf))
                .andExpect(status().isUnauthorized()).andReturn();
        MvcResult unknownUser = mockMvc.perform(login("unknown", "wrong", csrf))
                .andExpect(status().isUnauthorized()).andReturn();

        assertThat(wrongPassword.getResponse().getContentAsString())
                .isEqualTo(unknownUser.getResponse().getContentAsString());
        assertNoAuthCookies(wrongPassword);
        assertNoAuthCookies(unknownUser);
    }

    @Test
    void bannedAccountCannotLogin() throws Exception {
        jdbc.update("UPDATE users SET status = 'BANNED' WHERE id = ?", USER_ID);
        MvcResult denied = mockMvc.perform(login("alice", PASSWORD, csrfCookie()))
                .andExpect(status().isForbidden()).andReturn();
        assertNoAuthCookies(denied);
    }

    @Test
    void missingAndMismatchedCsrfAreRejected() throws Exception {
        MvcResult missing = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"correct password\"}"))
                .andExpect(status().isForbidden()).andReturn();
        Cookie csrf = csrfCookie();
        MvcResult mismatched = mockMvc.perform(post("/api/auth/login").cookie(csrf)
                        .header("X-XSRF-TOKEN", "wrong").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"correct password\"}"))
                .andExpect(status().isForbidden()).andReturn();
        assertNoAuthCookies(missing);
        assertNoAuthCookies(mismatched);
    }

    @Test
    void invalidJsonAndInvalidFieldsReturnBadRequestWithoutAuthCookies() throws Exception {
        Cookie csrf = csrfCookie();
        for (String body : new String[]{"{", "{}", "{\"username\":\"alice\",\"password\":\"\"}",
                "{\"username\":\"alice\",\"password\":\"" + "é".repeat(37) + "\"}"}) {
            MvcResult invalid = mockMvc.perform(post("/api/auth/login").cookie(csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andReturn();
            assertNoAuthCookies(invalid);
        }
    }

    @Test
    void corsAllowsOnlyConfiguredOriginWithCredentials() throws Exception {
        mockMvc.perform(options("/api/auth/login").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mockMvc.perform(options("/api/auth/login").header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void rejectsUnsafeCookieAndCorsConfiguration() {
        assertThatThrownBy(() -> new SecurityConfig(false, "None", "http://localhost:5173"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SameSite=None");
        assertThatThrownBy(() -> new SecurityConfig(true, "Lax", "*"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit origins");
    }

    private Cookie csrfCookie() throws Exception {
        MvcResult bootstrap = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent()).andReturn();
        Cookie csrf = bootstrap.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        assertThat(csrf.isHttpOnly()).isFalse();
        assertThat(csrf.getSecure()).isTrue();
        return csrf;
    }

    private MockHttpServletRequestBuilder login(String username, String password, Cookie csrf) {
        return post("/api/auth/login").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private void assertNoAuthCookies(MvcResult response) {
        assertThat(response.getResponse().getCookie("access_token")).isNull();
        assertThat(response.getResponse().getCookie("refresh_token")).isNull();
    }
}
