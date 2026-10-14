package com.reallive.userservice.user.controller;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.client.channel.ChannelClientResolver;
import com.reallive.userservice.common.security.CustomJwtDecoder;
import com.reallive.userservice.common.service.R2StorageService;
import com.reallive.userservice.user.entity.UserCategoryPreferenceEntity;
import com.reallive.userservice.user.entity.UserCategoryPreferenceId;
import com.reallive.userservice.user.repository.UserCategoryPreferenceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:category_preferences_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.open-in-view=false",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ActiveProfiles("test")
class CategoryPreferenceControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCategoryPreferenceRepository userCategoryPreferenceRepository;

    @MockitoBean
    private CustomJwtDecoder jwtDecoder;

    @MockitoBean
    private ChannelClientResolver channelClientResolver;

    @MockitoBean
    private R2StorageService storage;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        userCategoryPreferenceRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void getCategoryPreferencesReturnsUnauthorizedWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/users/me/category-preferences"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void getCategoryPreferencesReturnsCategoriesForActiveUser() throws Exception {
        UserEntity user = createUser("testuser", UserStatus.ACTIVE);
        UUID categoryId1 = UUID.randomUUID();
        UUID categoryId2 = UUID.randomUUID();

        userCategoryPreferenceRepository.save(UserCategoryPreferenceEntity.builder()
                .id(UserCategoryPreferenceId.builder().userId(user.getId()).categoryId(categoryId1).build())
                .user(user)
                .build());
        userCategoryPreferenceRepository.save(UserCategoryPreferenceEntity.builder()
                .id(UserCategoryPreferenceId.builder().userId(user.getId()).categoryId(categoryId2).build())
                .user(user)
                .build());

        authenticate(user.getId().toString());

        mockMvc.perform(get("/api/users/me/category-preferences").cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("User category preferences retrieved successfully"))
                .andExpect(jsonPath("$.result.categoryIds", hasSize(2)))
                .andExpect(jsonPath("$.result.categoryIds", containsInAnyOrder(categoryId1.toString(), categoryId2.toString())));
    }

    @Test
    void getCategoryPreferencesReturnsEmptyListWhenNoPreferencesConfigured() throws Exception {
        UserEntity user = createUser("testuser", UserStatus.ACTIVE);
        authenticate(user.getId().toString());

        mockMvc.perform(get("/api/users/me/category-preferences").cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.categoryIds", hasSize(0)));
    }

    @Test
    void getCategoryPreferencesReturnsNotFoundWhenUserDoesNotExist() throws Exception {
        UUID nonExistentUserId = UUID.randomUUID();
        authenticate(nonExistentUserId.toString());

        mockMvc.perform(get("/api/users/me/category-preferences").cookie(accessCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value(1004));
    }

    @Test
    void getCategoryPreferencesReturnsForbiddenWhenUserIsBanned() throws Exception {
        UserEntity user = createUser("banneduser", UserStatus.BANNED);
        authenticate(user.getId().toString());

        mockMvc.perform(get("/api/users/me/category-preferences").cookie(accessCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value(1003));
    }

    private UserEntity createUser(String username, UserStatus status) {
        return userRepository.saveAndFlush(UserEntity.builder()
                .username(username)
                .email(username + "@example.com")
                .passwordHash("password_hash")
                .status(status)
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
