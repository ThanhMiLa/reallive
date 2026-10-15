package com.reallive.userservice.streamer.controller.internal;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.streamer.entity.StreamerEntity;
import com.reallive.userservice.streamer.entity.StreamerStatus;
import com.reallive.userservice.streamer.repository.StreamerRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:active_streamer_lookup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.open-in-view=false",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ActiveProfiles("test")
@Transactional
class StreamerControllerIntegrationTest {

    private static final String CONTEXT_PATH = "/user";
    private static final String ENDPOINT = CONTEXT_PATH + "/internal/api/streamers/by-user/{userId}/active";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository users;
    @Autowired
    private StreamerRepository streamers;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void returnsTheRequestedUsersStreamerIdWithoutAnAccessCookie() throws Exception {
        UserEntity user = createUser();
        StreamerEntity streamer = createStreamer(user, StreamerStatus.ACTIVE);
        createStreamer(createUser(), StreamerStatus.ACTIVE);
        assertNotEquals(user.getId(), streamer.getId());

        mvc.perform(get(ENDPOINT, user.getId()).contextPath(CONTEXT_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.length()").value(1))
                .andExpect(jsonPath("$.result.streamerId").value(streamer.getId().toString()))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        mvc.perform(get(ENDPOINT, streamer.getId()).contextPath(CONTEXT_PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.USER_NOT_FOUND.getCode()));
    }

    @Test
    void rejectsAUserWithoutAStreamerRecord() throws Exception {
        UUID userId = createUser().getId();

        mvc.perform(get(ENDPOINT, userId).contextPath(CONTEXT_PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value(ErrorCode.STREAMER_NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.message").value("Streamer not found"))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }

    @Test
    void rejectsAnUnknownUserId() throws Exception {
        mvc.perform(get(ENDPOINT, UUID.randomUUID()).contextPath(CONTEXT_PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.USER_NOT_FOUND.getCode()));
    }

    @ParameterizedTest
    @EnumSource(value = StreamerStatus.class, names = {"PENDING", "SUSPENDED", "BANNED"})
    void rejectsEveryNonActiveStreamerStatus(StreamerStatus streamerStatus) throws Exception {
        UserEntity user = createUser();
        createStreamer(user, streamerStatus);

        mvc.perform(get(ENDPOINT, user.getId()).contextPath(CONTEXT_PATH))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value(ErrorCode.STREAMER_NOT_ACTIVE.getCode()))
                .andExpect(jsonPath("$.message").value("Streamer is not active"))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }

    @Test
    void readsCurrentDatabaseStatusOnEachRequest() throws Exception {
        UserEntity user = createUser();
        StreamerEntity streamer = createStreamer(user, StreamerStatus.ACTIVE);
        UUID userId = user.getId();
        UUID streamerId = streamer.getId();

        mvc.perform(get(ENDPOINT, userId).contextPath(CONTEXT_PATH))
                .andExpect(status().isOk());

        jdbc.update("UPDATE streamers SET status = 'SUSPENDED' WHERE id = ?", streamerId);
        entityManager.clear();

        mvc.perform(get(ENDPOINT, userId).contextPath(CONTEXT_PATH))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.STREAMER_NOT_ACTIVE.getCode()));
    }

    @Test
    void rejectsMalformedUserIds() throws Exception {
        mvc.perform(get(ENDPOINT, "invalid-uuid").contextPath(CONTEXT_PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(4000));
    }

    @Test
    void keepsUserFacingEndpointsAuthenticated() throws Exception {
        mvc.perform(get(CONTEXT_PATH + "/api/users/me").contextPath(CONTEXT_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void doesNotPermitOtherInternalPathsOrMethods() throws Exception {
        UUID userId = UUID.randomUUID();
        mvc.perform(get(CONTEXT_PATH + "/internal/api/streamers/by-user/{userId}", userId)
                        .contextPath(CONTEXT_PATH))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ENDPOINT, userId).contextPath(CONTEXT_PATH))
                .andExpect(status().isUnauthorized());
    }

    private UserEntity createUser() {
        String identifier = UUID.randomUUID().toString();
        return users.saveAndFlush(UserEntity.builder()
                .username(identifier)
                .email(identifier + "@example.com")
                .passwordHash("test-hash")
                .emailVerified(true)
                .build());
    }

    private StreamerEntity createStreamer(UserEntity user, StreamerStatus streamerStatus) {
        return streamers.saveAndFlush(StreamerEntity.builder()
                .user(user)
                .status(streamerStatus)
                .build());
    }
}
