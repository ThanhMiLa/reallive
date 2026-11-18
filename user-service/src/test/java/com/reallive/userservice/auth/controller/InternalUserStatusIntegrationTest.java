package com.reallive.userservice.auth.controller;

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

import java.util.Locale;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:internal_user_status;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalUserStatusIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ProfileRepository profiles;

    @Test
    void searchesCaseInsensitiveDisplayNameFragmentsAndKeepsDuplicateNames() throws Exception {
        String shared = "Viewer_" + UUID.randomUUID();
        var first = createProfile(shared, UserStatus.BANNED);
        var second = createProfile(shared, UserStatus.ACTIVE);
        createProfile(shared.replaceFirst("_", "X"), UserStatus.ACTIVE);
        String fragment = shared.substring(4).toUpperCase(Locale.ROOT);
        String path = "/internal/api/users/identities";

        mvc.perform(get(path).param("displayName", fragment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.content[*].userId", containsInAnyOrder(
                        first.getId().toString(), second.getId().toString())))
                .andExpect(jsonPath("$.result.content[0].displayName").value(shared))
                .andExpect(jsonPath("$.result.content[0].email").doesNotExist());
        mvc.perform(get(path).param("displayName", fragment).param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content.length()").value(1))
                .andExpect(jsonPath("$.result.totalPages").value(2));
        mvc.perform(get(path).param("displayName", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content").isEmpty());
        mvc.perform(post(path).param("displayName", fragment)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/api/users/chat-profiles").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userIds\":[\"%s\"]}".formatted(first.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result[0].username").value(first.getUsername()));
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
