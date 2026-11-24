package com.reallive.userservice.auth.controller;

import com.nimbusds.jwt.SignedJWT;
import com.reallive.userservice.auth.adapter.redis.AuthStateStore;
import com.reallive.userservice.auth.entity.RoleEntity;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.user.repository.ProfileRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false"
})
@ActiveProfiles("test")
class RegistrationIntegrationTest {
    @MockitoBean
    private RedisAuthoritiesReader authAuthoritiesReader;

    @BeforeEach
    void setUpAuthAuthorities() {
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @MockitoBean
    private AuthStateStore authStateStore;

    private static final String SCHEMA = "registration_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository users;
    @Autowired
    private ProfileRepository profiles;
    @Autowired
    private RoleRepository roles;
    @Autowired
    private ObjectMapper mapper;
    private MockMvc mvc;
    private String username;
    private String email;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("registration.test.database-url",
                "jdbc:h2:mem:registration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
        registry.add("spring.datasource.username", () -> System.getProperty("registration.test.database-username", "sa"));
        registry.add("spring.datasource.password", () -> System.getProperty("registration.test.database-password", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "\"" + SCHEMA + "\"");
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        roles.findByName("USER").orElseGet(() -> roles.saveAndFlush(RoleEntity.builder().name("USER").build()));
        username = "register-" + UUID.randomUUID();
        email = UUID.randomUUID() + "@example.com";
    }

    @AfterAll
    static void cleanUp(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void registrationPersistsUserAndProfileAndIssuesAuthCookies() throws Exception {
        MvcResult result = register("password123").andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(cookie().exists("access_token"))
                .andExpect(cookie().exists("refresh_token"))
                .andExpect(cookie().httpOnly("access_token", true)).andReturn();
        var user = users.findByUsername(username).orElseThrow();

        assertEquals(email, user.getEmail());
        assertNotEquals("password123", user.getPasswordHash());
        assertEquals(username, profiles.findByUserId(user.getId()).orElseThrow().getDisplayName());
        var response = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(response.path("result").path("accessToken").isNull());
        assertTrue(response.path("result").path("refreshToken").isNull());
        assertEquals(user.getId().toString(), SignedJWT.parse(result.getResponse().getCookie("access_token").getValue())
                .getJWTClaimsSet().getSubject());
    }

    @Test
    void duplicateUsernameReturnsConflict() throws Exception {
        register("password123").andExpect(status().isOk());
        email = UUID.randomUUID() + "@example.com";

        register("password123").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.USERNAME_ALREADY_EXISTS.getCode()));

    }

    @Test
    void duplicateEmailReturnsConflict() throws Exception {
        register("password123").andExpect(status().isOk());
        username = "register-" + UUID.randomUUID();

        register("password123").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.EMAIL_ALREADY_EXISTS.getCode()));

    }

    @Test
    void passwordMismatchDoesNotSaveUser() throws Exception {
        register("different-password").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PASSWORD_NOT_MATCH.getCode()));
        assertFalse(users.existsByUsername(username));
    }

    @Test
    void invalidInputDoesNotSaveUser() throws Exception {
        username = "a";
        register("password123").andExpect(status().isBadRequest());
        assertFalse(users.existsByUsername(username));
    }

    private ResultActions register(String confirmation) throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", username, "email", email,
                        "password", "password123", "confirmPassword", confirmation))));
    }
}
