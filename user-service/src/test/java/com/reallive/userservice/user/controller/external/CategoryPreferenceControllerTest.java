package com.reallive.userservice.user.controller.external;

import com.reallive.userservice.auth.adapter.redis.AuthStateStore;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.client.channel.ChannelClientResolver;
import com.reallive.userservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.security.CustomJwtDecoder;
import com.reallive.userservice.common.service.R2StorageService;
import com.reallive.userservice.user.entity.UserCategoryPreferenceEntity;
import com.reallive.userservice.user.entity.UserCategoryPreferenceId;
import com.reallive.userservice.user.repository.UserCategoryPreferenceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:category_preferences_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.open-in-view=false",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ActiveProfiles("test")
class CategoryPreferenceControllerTest {
    @MockitoBean
    private RedisAuthoritiesReader authAuthoritiesReader;

    @BeforeEach
    void setUpAuthAuthorities() {
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @MockitoBean
    private AuthStateStore authStateStore;

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
    void getPreferencesDoesNotRepeatGatewayAccountStatusCheck() throws Exception {
        UserEntity user = createUser("banneduser", UserStatus.BANNED);
        authenticate(user.getId().toString());

        mockMvc.perform(get("/api/users/me/category-preferences").cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000));
    }

    @Test
    void updateCategoryPreferencesReturnsUnauthorizedWhenUnauthenticated() throws Exception {
        mockMvc.perform(put("/api/users/me/category-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void updateCategoryPreferencesRejectsEmptyCategoryIds() throws Exception {
        UserEntity user = createUser("testuser", UserStatus.ACTIVE);
        authenticate(user.getId().toString());

        mockMvc.perform(put("/api/users/me/category-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryIds\":[]}")
                        .cookie(accessCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(4000));
    }

    @Test
    void updateCategoryPreferencesRejectsMissingCategoryIds() throws Exception {
        UserEntity user = createUser("testuser", UserStatus.ACTIVE);
        authenticate(user.getId().toString());

        mockMvc.perform(put("/api/users/me/category-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(accessCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(4000));
    }

    @Test
    void updateCategoryPreferencesRejectsNullElementInCategoryIds() throws Exception {
        UserEntity user = createUser("testuser", UserStatus.ACTIVE);
        authenticate(user.getId().toString());

        mockMvc.perform(put("/api/users/me/category-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryIds\":[null]}")
                        .cookie(accessCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(4000));
    }

    @Test
    void updateCategoryPreferencesRejectsMoreThan20Categories() throws Exception {
        UserEntity user = createUser("testuser", UserStatus.ACTIVE);
        authenticate(user.getId().toString());

        List<String> ids = IntStream.range(0, 21)
                .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .toList();

        mockMvc.perform(put("/api/users/me/category-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryIds\":[" + String.join(",", ids) + "]}")
                        .cookie(accessCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(4000));
    }

    @Test
    void updateCategoryPreferencesRejectsInvalidUuidFormat() throws Exception {
        UserEntity user = createUser("testuser", UserStatus.ACTIVE);
        authenticate(user.getId().toString());

        mockMvc.perform(put("/api/users/me/category-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryIds\":[\"not-a-valid-uuid\"]}")
                        .cookie(accessCookie()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateCategoryPreferencesReturnsNotFoundWhenUserDoesNotExist() throws Exception {
        UUID nonExistentUserId = UUID.randomUUID();
        authenticate(nonExistentUserId.toString());

        mockMvc.perform(put("/api/users/me/category-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryIds\":[\"" + UUID.randomUUID() + "\"]}")
                        .cookie(accessCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value(1004));

        verifyNoInteractions(channelClientResolver);
    }

    @Test
    void updatePreferencesDoesNotRepeatGatewayAccountStatusCheck() throws Exception {
        UserEntity user = createUser("banneduser", UserStatus.BANNED);
        authenticate(user.getId().toString());

        mockMvc.perform(put("/api/users/me/category-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryIds\":[\"" + UUID.randomUUID() + "\"]}")
                        .cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000));

        verify(channelClientResolver).validateCategories(any());
    }

    @Test
    void updateCategoryPreferencesReturnsBadRequestWhenChannelServiceRejectsCategoryNotFound() throws Exception {
        UserEntity user = createUser("testuser", UserStatus.ACTIVE);
        authenticate(user.getId().toString());

        when(channelClientResolver.validateCategories(any()))
                .thenThrow(new AppException(ErrorCode.CATEGORY_NOT_FOUND));

        mockMvc.perform(put("/api/users/me/category-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryIds\":[\"" + UUID.randomUUID() + "\"]}")
                        .cookie(accessCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(1042))
                .andExpect(jsonPath("$.message").value("Category not found"));

        assertTrue(userCategoryPreferenceRepository.findAllById_UserId(user.getId()).isEmpty());
    }

    @Test
    void updateCategoryPreferencesReturnsBadRequestWhenChannelServiceRejectsCategoryNotActive() throws Exception {
        UserEntity user = createUser("testuser", UserStatus.ACTIVE);
        authenticate(user.getId().toString());

        when(channelClientResolver.validateCategories(any()))
                .thenThrow(new AppException(ErrorCode.CATEGORY_NOT_ACTIVE));

        mockMvc.perform(put("/api/users/me/category-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryIds\":[\"" + UUID.randomUUID() + "\"]}")
                        .cookie(accessCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(1043))
                .andExpect(jsonPath("$.message").value("Category is not active"));

        assertTrue(userCategoryPreferenceRepository.findAllById_UserId(user.getId()).isEmpty());
    }

    @Test
    void updateCategoryPreferencesReturnsInternalServerErrorWhenChannelServiceUnavailable() throws Exception {
        UserEntity user = createUser("testuser", UserStatus.ACTIVE);
        authenticate(user.getId().toString());

        when(channelClientResolver.validateCategories(any()))
                .thenThrow(new AppException(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE));

        mockMvc.perform(put("/api/users/me/category-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryIds\":[\"" + UUID.randomUUID() + "\"]}")
                        .cookie(accessCookie()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value(1037))
                .andExpect(jsonPath("$.message").value("Channel service unavailable"));

        assertTrue(userCategoryPreferenceRepository.findAllById_UserId(user.getId()).isEmpty());
    }

    @Test
    void updateCategoryPreferencesSucceedsDeduplicatesAndReplacesExistingPreferences() throws Exception {
        UserEntity user = createUser("testuser", UserStatus.ACTIVE);
        authenticate(user.getId().toString());

        // Pre-existing preference
        UUID oldCategory = UUID.randomUUID();
        userCategoryPreferenceRepository.saveAndFlush(UserCategoryPreferenceEntity.builder()
                .id(UserCategoryPreferenceId.builder().userId(user.getId()).categoryId(oldCategory).build())
                .user(user)
                .build());

        UUID cat1 = UUID.randomUUID();
        UUID cat2 = UUID.randomUUID();

        when(channelClientResolver.validateCategories(Set.of(cat1, cat2))).thenReturn(true);

        // Send duplicate cat1 in payload: [cat1, cat2, cat1]
        String payload = String.format("{\"categoryIds\":[\"%s\",\"%s\",\"%s\"]}", cat1, cat2, cat1);

        mockMvc.perform(put("/api/users/me/category-preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .cookie(accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("User category preferences updated successfully"))
                .andExpect(jsonPath("$.result.categoryIds", hasSize(2)))
                .andExpect(jsonPath("$.result.categoryIds[0]").value(cat1.toString()))
                .andExpect(jsonPath("$.result.categoryIds[1]").value(cat2.toString()));

        // Verify in database: old category is gone, only cat1 and cat2 exist
        List<UserCategoryPreferenceEntity> persisted = userCategoryPreferenceRepository.findAllById_UserId(user.getId());
        assertEquals(2, persisted.size());
        Set<UUID> persistedIds = Set.of(
                persisted.get(0).getId().getCategoryId(),
                persisted.get(1).getId().getCategoryId()
        );
        assertEquals(Set.of(cat1, cat2), persistedIds);
    }

    @Test
    void internalLookupReadsPreferencesWithoutCookiesAndDoesNotCallChannelService() throws Exception {
        UserEntity user = createUser("internaluser", UserStatus.ACTIVE);
        UUID category = UUID.randomUUID();
        userCategoryPreferenceRepository.saveAndFlush(UserCategoryPreferenceEntity.builder()
                .id(UserCategoryPreferenceId.builder().userId(user.getId()).categoryId(category).build())
                .user(user).build());
        mockMvc.perform(get("/internal/api/users/{userId}/category-preferences", user.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.categoryIds[0]").value(category.toString()));
        verifyNoInteractions(channelClientResolver, jwtDecoder);
    }

    @Test
    void internalLookupDistinguishesEmptyPreferencesMissingUserAndInvalidUuid() throws Exception {
        UserEntity user = createUser("internalempty", UserStatus.ACTIVE);
        mockMvc.perform(get("/internal/api/users/{userId}/category-preferences", user.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.categoryIds", hasSize(0)));
        mockMvc.perform(get("/internal/api/users/{userId}/category-preferences", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1004));
        mockMvc.perform(get("/internal/api/users/not-a-uuid/category-preferences"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/internal/api/users/{userId}/category-preferences", user.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me/category-preferences"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(channelClientResolver);
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
