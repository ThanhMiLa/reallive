package com.reallive.userservice.user.controller.internal;

import tools.jackson.databind.ObjectMapper;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.user.entity.ProfileEntity;
import com.reallive.userservice.user.repository.ProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:internal_user_status;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalUserAccountIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ProfileRepository profiles;
    @Autowired ObjectMapper objectMapper;

    @Test
    void searchesFollowerDisplayNameFragmentsAndPaginatesDuplicateNames() throws Exception {
        String shared = "Viewer_" + UUID.randomUUID();
        var first = createProfile(shared, UserStatus.BANNED);
        var second = createProfile(shared, UserStatus.ACTIVE);
        var similar = createProfile(shared.replaceFirst("_", "X"), UserStatus.ACTIVE);
        createProfile(shared, UserStatus.ACTIVE);
        var followerIds = List.of(first.getId(), second.getId(), similar.getId());
        String fragment = shared.substring(4).toUpperCase(Locale.ROOT);
        String path = "/internal/api/users/identities";

        mvc.perform(searchIdentities(path, fragment, followerIds, 0, 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.content[*].userId", containsInAnyOrder(
                        first.getId().toString(), second.getId().toString())))
                .andExpect(jsonPath("$.result.content[0].displayName").value(shared))
                .andExpect(jsonPath("$.result.content[0].username").doesNotExist())
                .andExpect(jsonPath("$.result.content[0].email").doesNotExist());
        mvc.perform(searchIdentities(path, fragment, followerIds, 1, 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content.length()").value(1))
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.totalPages").value(2));
        mvc.perform(searchIdentities(path, UUID.randomUUID().toString(), followerIds, 0, 20))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content").isEmpty());
        mvc.perform(searchIdentities(path, fragment, List.of(), 0, 20))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content").isEmpty());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("displayName", fragment, "page", 0, "size", 20))))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path).param("displayName", fragment)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/api/users/chat-profiles").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userIds\":[\"%s\"]}".formatted(first.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result[0].username").value(first.getUsername()));
    }

    private MockHttpServletRequestBuilder searchIdentities(
            String path, String displayName, List<UUID> followerIds, int page, int size) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(
                Map.of("displayName", displayName, "followerIds", followerIds, "page", page, "size", size)));
    }

    private UserEntity createProfile(String displayName, UserStatus state) {
        String username = UUID.randomUUID().toString();
        var user = users.saveAndFlush(UserEntity.builder().username(username)
                .email(username + "@example.com").passwordHash("test-hash").status(state).build());
        profiles.saveAndFlush(ProfileEntity.builder().user(user).displayName(displayName).build());
        return user;
    }

    @Test
    void readsAccountStatusWithoutCredentialsAndRestrictsMethod() throws Exception {
        for (UserStatus state : UserStatus.values()) {
            String name = UUID.randomUUID().toString();
            UUID id = users.saveAndFlush(UserEntity.builder()
                    .username(name).email(name + "@example.com").passwordHash("test-hash").status(state).build()).getId();
            mvc.perform(get("/internal/api/users/{userId}/status", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.userId").value(id.toString()))
                    .andExpect(jsonPath("$.result.status").value(state.name()));
            mvc.perform(post("/internal/api/users/{userId}/status", id))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/internal/api/users/{userId}/status", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1004));
    }
}
