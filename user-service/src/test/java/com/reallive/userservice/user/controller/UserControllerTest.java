package com.reallive.userservice.user.controller;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.security.CustomJwtDecoder;
import com.reallive.userservice.user.entity.ProfileEntity;
import com.reallive.userservice.user.repository.ProfileRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user_profile_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.open-in-view=false",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ActiveProfiles("test")
class UserControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @MockitoBean
    private CustomJwtDecoder jwtDecoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        profileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void getMyProfileReturnsExactlyTheRequestedFields() throws Exception {
        UserEntity user = createUser("viewer");
        profileRepository.saveAndFlush(ProfileEntity.builder()
                .user(user)
                .displayName("Viewer")
                .avatarUrl("https://example.com/avatar.png")
                .dob(LocalDate.of(2000, 1, 1))
                .build());
        authenticate(user.getId().toString());

        mockMvc.perform(get("/api/users/me").cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.length()").value(6))
                .andExpect(jsonPath("$.result.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.result.displayName").value("Viewer"))
                .andExpect(jsonPath("$.result.email").value("viewer@example.com"))
                .andExpect(jsonPath("$.result.avatarUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.result.dob").value("2000-01-01"))
                .andExpect(jsonPath("$.result.create_at").value(user.getCreatedAt().toString()));
    }

    @Test
    void getMyProfileReturnsNullForOptionalFields() throws Exception {
        UserEntity user = createUser("viewer");
        profileRepository.saveAndFlush(ProfileEntity.builder().user(user).displayName("Viewer").build());
        authenticate(user.getId().toString());

        mockMvc.perform(get("/api/users/me").cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(hasKey("avatarUrl")))
                .andExpect(jsonPath("$.result.avatarUrl").value(nullValue()))
                .andExpect(jsonPath("$.result").value(hasKey("dob")))
                .andExpect(jsonPath("$.result.dob").value(nullValue()));
    }

    @Test
    void getMyProfileIgnoresAnotherUserIdSuppliedInTheQuery() throws Exception {
        UserEntity viewer = createUser("viewer");
        UserEntity otherUser = createUser("other_user");
        profileRepository.saveAndFlush(ProfileEntity.builder().user(viewer).displayName("Viewer").build());
        profileRepository.saveAndFlush(ProfileEntity.builder().user(otherUser).displayName("Other User").build());
        authenticate(viewer.getId().toString());

        mockMvc.perform(get("/api/users/me").param("userId", otherUser.getId().toString()).cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.userId").value(viewer.getId().toString()))
                .andExpect(jsonPath("$.result.email").value("viewer@example.com"));
    }

    @Test
    void getMyProfileRejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
    }

    @Test
    void getMyProfileRejectsInvalidTokens() throws Exception {
        when(jwtDecoder.decode("valid-access-token")).thenThrow(new BadJwtException("Invalid access token"));

        mockMvc.perform(get("/api/users/me").cookie(accessCookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-uuid"})
    void getMyProfileRejectsMissingOrInvalidUserIdsInTheToken(String subject) throws Exception {
        authenticate(subject);

        mockMvc.perform(get("/api/users/me").cookie(accessCookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
    }

    @Test
    void getMyProfileReturnsNotFoundWhenTheProfileIsMissing() throws Exception {
        UserEntity user = createUser("viewer");
        authenticate(user.getId().toString());

        mockMvc.perform(get("/api/users/me").cookie(accessCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.PROFILE_NOT_FOUND.getCode()));
    }

    @Test
    void getMyProfileRejectsBannedAccounts() throws Exception {
        UserEntity user = createUser("viewer");
        user.setStatus(UserStatus.BANNED);
        user = userRepository.saveAndFlush(user);
        profileRepository.saveAndFlush(ProfileEntity.builder().user(user).displayName("Viewer").build());
        authenticate(user.getId().toString());

        mockMvc.perform(get("/api/users/me").cookie(accessCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.USER_NOT_ACTIVE.getCode()));
    }

    private UserEntity createUser(String username) {
        return userRepository.saveAndFlush(UserEntity.builder()
                .username(username)
                .email(username + "@example.com")
                .passwordHash("test-password-hash")
                .build());
    }

    private void authenticate(String subject) {
        Jwt.Builder jwt = Jwt.withTokenValue("valid-access-token")
                .header("alg", "HS512")
                .claim("scope", "ROLE_USER");
        if (subject != null) {
            jwt.subject(subject);
        }
        when(jwtDecoder.decode("valid-access-token")).thenReturn(jwt.build());
    }

    private Cookie accessCookie() {
        return new Cookie("access_token", "valid-access-token");
    }
}
