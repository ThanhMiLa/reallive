package com.reallive.channelservice.channel.controller.external;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.channelservice.client.user.UserCategoryPreferenceResolver;
import com.reallive.channelservice.common.adapter.redis.RedisAuthoritiesReader;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "server.servlet.context-path=/channel",
        "spring.datasource.url=${channel.test.database-url:jdbc:h2:mem:channel_following_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${channel.test.database-username:sa}",
        "spring.datasource.password=${channel.test.database-password:}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ChannelFollowingIntegrationTest {

    private static final String PATH = "/channel/api/channels";
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 1, 1, 0, 0);
    @MockitoBean
    private RedisAuthoritiesReader authorities;
    @MockitoBean
    private UserCategoryPreferenceResolver interests;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void authenticateAuthorities() {
        when(authorities.read(any())).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @ParameterizedTest
    @CsvSource({"FOLLOWER_COUNT,DESC,Popular,Smaller", "FOLLOWER_COUNT,ASC,Smaller,Popular",
            "FOLLOWED_AT,DESC,Smaller,Popular", "FOLLOWED_AT,ASC,Popular,Smaller"})
    void sortsOneCriterionBeforePagination(String sortBy, String direction, String first, String second) throws Exception {
        UUID user = UUID.randomUUID();
        UUID popular = insertChannel("Popular", 300);
        UUID smaller = insertChannel("Smaller", 100);
        follow(popular, user, TIME);
        follow(smaller, user, TIME.plusDays(1));
        UUID otherUser = UUID.randomUUID();
        follow(popular, otherUser, TIME.plusDays(3));
        follow(smaller, otherUser, TIME.minusDays(1));
        insertChannel("Not followed", 1000);
        for (int page = 0; page < 2; page++) {
            mvc.perform(list(user).param("sortBy", sortBy).param("sortDirection", direction)
                            .param("page", String.valueOf(page)).param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.content[0].name").value(page == 0 ? first : second))
                    .andExpect(jsonPath("$.result.totalElements").value(2))
                    .andExpect(jsonPath("$.result.totalPages").value(2));
        }
        mvc.perform(list(user).param("page", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content", hasSize(0)))
                .andExpect(jsonPath("$.result.totalElements").value(2));
        verifyNoInteractions(interests);
    }

    @Test
    void defaultsToFollowerDescendingAndReadsOtherUsersFollowTimeIndependently() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID popular = insertChannel("Popular", 300);
        UUID smaller = insertChannel("Smaller", 100);
        jdbc.update("UPDATE channels SET banner_url = ?, avatar_url = ? WHERE id = ?",
                "https://example.com/banner.png", "https://example.com/avatar.png", popular);
        follow(popular, user, TIME);
        follow(smaller, user, TIME.plusDays(1));
        follow(popular, other, TIME.plusDays(2));
        follow(smaller, other, TIME);
        mvc.perform(list(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0]", aMapWithSize(4)))
                .andExpect(jsonPath("$.result.content[0].id").value(popular.toString()))
                .andExpect(jsonPath("$.result.content[0].name").value("Popular"))
                .andExpect(jsonPath("$.result.content[0].avatarUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.result.content[0].numberOfFollower").value(300))
                .andExpect(jsonPath("$.result.content[0].bannerUrl").doesNotExist());
        mvc.perform(list(user).param("sortDirection", "ASC")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("Smaller"));
        mvc.perform(list(user).param("sortBy", "FOLLOWED_AT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("Smaller"));
        mvc.perform(list(other).param("sortBy", "FOLLOWED_AT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("Popular"));
        verifyNoInteractions(interests);
    }

    @ParameterizedTest
    @CsvSource({"FOLLOWER_COUNT,ASC", "FOLLOWER_COUNT,DESC", "FOLLOWED_AT,ASC", "FOLLOWED_AT,DESC"})
    void tiesUseChannelCreationWithoutTheOtherBusinessCriterion(String sortBy, String direction) throws Exception {
        UUID user = UUID.randomUUID();
        UUID older = insertChannel("Older", sortBy.equals("FOLLOWER_COUNT") ? 100 : 1000);
        UUID newer = insertChannel("Newer", 100);
        jdbc.update("UPDATE channels SET created_at = ? WHERE id = ?", TIME.minusDays(1), older);
        jdbc.update("UPDATE channels SET created_at = ? WHERE id = ?", TIME, newer);
        follow(older, user, TIME);
        follow(newer, user, sortBy.equals("FOLLOWER_COUNT") ? TIME.minusDays(1) : TIME);
        mvc.perform(list(user).param("sortBy", sortBy).param("sortDirection", direction))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content[0].name").value("Newer"));
    }

    @Test
    void excludesOwnBannedAndDeletedChannelsAndOtherUsersMembership() throws Exception {
        UUID user = UUID.randomUUID();
        UUID visible = insertChannel("Visible", 100);
        UUID own = insertChannel("Own", 200);
        jdbc.update("UPDATE channels SET user_id = ? WHERE id = ?", user, own);
        UUID banned = insertChannel("Banned", 300);
        UUID deleted = insertChannel("Deleted", 400);
        jdbc.update("UPDATE channels SET status = 'BANNED' WHERE id = ?", banned);
        jdbc.update("UPDATE channels SET deleted_at = ? WHERE id = ?", TIME, deleted);
        for (UUID channel : List.of(own, banned, deleted)) {
            follow(channel, user, TIME);
        }
        follow(visible, user, TIME);
        follow(insertChannel("Other user only", 1000), UUID.randomUUID(), TIME);
        mvc.perform(list(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.content[0].id").value(visible.toString()));
    }

    @Test
    void followsUnfollowsAndRefollowsMoveChannelsAndRefreshFollowTimestamp() throws Exception {
        UUID user = UUID.randomUUID();
        UUID first = insertChannel("First", 100);
        UUID second = insertChannel("Second", 100);
        follow(first, user, TIME);
        follow(second, user, TIME.plusDays(1));
        mvc.perform(list(user).param("sortBy", "FOLLOWED_AT"))
                .andExpect(jsonPath("$.result.content[0].name").value("Second"));
        mvc.perform(delete(PATH + "/{channelId}/follow", first).contextPath("/channel")
                        .cookie(accessCookie(user.toString()))).andExpect(status().isOk());
        mvc.perform(list(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.content[0].name").value("Second"));
        mvc.perform(put(PATH + "/{channelId}/follow", first).contextPath("/channel")
                        .cookie(accessCookie(user.toString()))).andExpect(status().isOk());
        mvc.perform(list(user).param("sortBy", "FOLLOWED_AT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content[0].name").value("First"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"keyword", "categoryIds", "following", "userId", "sort"})
    void followingIgnoresParametersOutsideItsRequestModel(String name) throws Exception {
        UUID user = UUID.randomUUID();
        UUID channel = insertChannel("Visible", 10);
        follow(channel, user, TIME);
        mvc.perform(list(user).param(name, "anything"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.content[0].id").value(channel.toString()));
        verifyNoInteractions(interests);
    }

    @ParameterizedTest
    @CsvSource({"sortBy,INVALID", "sortBy,''", "sortBy,'FOLLOWER_COUNT,FOLLOWED_AT'", "sortDirection,INVALID",
            "sortDirection,''", "page,''", "size,''", "page,-1", "size,0", "size,101",
            "page,abc", "size,abc", "page,2147483648", "page,2147483647"})
    void validatesFollowingRequest(String name, String value) throws Exception {
        mvc.perform(list(UUID.randomUUID()).param(name, value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1004));
    }

    @Test
    void requiresAuthenticationAndAValidUserSubject() throws Exception {
        String path = PATH + "/following";
        mvc.perform(get(path).contextPath("/channel")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).contextPath("/channel").cookie(new Cookie("access_token", "bad-token")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).contextPath("/channel").cookie(accessCookie("not-a-uuid")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(interests);
    }

    @Test
    void usersWithNoFollowsReceiveAnEmptyPageAndDefaultMetadata() throws Exception {
        UUID user = UUID.randomUUID();
        follow(insertChannel("Someone else's follow", 1000), UUID.randomUUID(), TIME);
        mvc.perform(list(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(0)))
                .andExpect(jsonPath("$.result.totalElements").value(0))
                .andExpect(jsonPath("$.result.totalPages").value(0))
                .andExpect(jsonPath("$.result.page").value(0))
                .andExpect(jsonPath("$.result.size").value(20));
        verifyNoInteractions(interests);
    }

    @ParameterizedTest
    @CsvSource({"FOLLOWER_COUNT,ASC", "FOLLOWER_COUNT,DESC", "FOLLOWED_AT,ASC", "FOLLOWED_AT,DESC"})
    void identicalSortAndCreationValuesUseIdDescendingAcrossPages(String sortBy, String direction) throws Exception {
        UUID user = UUID.randomUUID();
        UUID lower = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higher = UUID.fromString("00000000-0000-0000-0000-000000000002");
        for (UUID id : List.of(lower, higher)) {
            UUID original = insertChannel(id.toString(), 100);
            jdbc.update("UPDATE channels SET id = ?, created_at = ? WHERE id = ?", id, TIME, original);
            follow(id, user, TIME);
        }
        for (int page = 0; page < 2; page++) {
            mvc.perform(list(user).param("sortBy", sortBy).param("sortDirection", direction)
                            .param("size", "1").param("page", String.valueOf(page)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.content[0].id").value((page == 0 ? higher : lower).toString()))
                    .andExpect(jsonPath("$.result.totalElements").value(2));
        }
        verifyNoInteractions(interests);
    }

    private MockHttpServletRequestBuilder list(UUID user) throws Exception {
        return get(PATH + "/following").contextPath("/channel")
                .cookie(accessCookie(user.toString()));
    }

    private UUID insertChannel(String name, long followerCount) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO channels (id, streamer_id, name, slug, status, follower_count) VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
                id, UUID.randomUUID(), name, id.toString(), followerCount);
        return id;
    }

    private void follow(UUID channel, UUID user, LocalDateTime time) {
        jdbc.update("INSERT INTO channel_followers (channel_id, user_id, followed_at) VALUES (?, ?, ?)", channel, user, time);
    }

    private Cookie accessCookie(String subject) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject(subject).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300))).build();
        var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signed.sign(new MACSigner("channel-following-test-secret-".repeat(4)));
        return new Cookie("access_token", signed.serialize());
    }
}
