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

import java.util.UUID;

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
    void findsExactUsernameWithStatusAndOptionalProfile() throws Exception {
        String username = "CaseSensitive-" + UUID.randomUUID();
        var user = users.saveAndFlush(UserEntity.builder().username(username)
                .email(username + "@example.com").passwordHash("test-hash").status(UserStatus.BANNED).build());
        String path = "/internal/api/users/by-username/identity";
        mvc.perform(get(path).param("username", username)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.result.status").value("BANNED"))
                .andExpect(jsonPath("$.result.displayName").doesNotExist());
        profiles.saveAndFlush(ProfileEntity.builder().user(user).displayName("Viewer").build());
        mvc.perform(get(path).param("username", username)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.displayName").value("Viewer"))
                .andExpect(jsonPath("$.result.username").value(username))
                .andExpect(jsonPath("$.result.email").doesNotExist());
        mvc.perform(get(path).param("username", username.toLowerCase(java.util.Locale.ROOT)))
                .andExpect(status().isNotFound());
        mvc.perform(post(path).param("username", username)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/api/users/chat-profiles").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userIds\":[\"%s\"]}".formatted(user.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result[0].username").value(username));
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
