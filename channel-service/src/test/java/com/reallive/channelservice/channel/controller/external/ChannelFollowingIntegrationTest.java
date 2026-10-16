package com.reallive.channelservice.channel.controller.external;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.channelservice.channel.dto.request.ChannelListRequest;
import com.reallive.channelservice.channel.service.ChannelService;
import com.reallive.channelservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @MockitoBean
    private RedisAuthoritiesReader authAuthoritiesReader;

    @BeforeEach
    void setUpAuthAuthorities() {
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static final String PATH = "/channel/api/channels";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChannelService channelService;

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void followingFilterRequiresAuthenticationForBothValues(String following) throws Exception {
        mvc.perform(get(PATH).contextPath("/channel").param("following", following))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }

    @ParameterizedTest
    @CsvSource({"true,Followed popular,Followed smaller", "false,Other user,New channel"})
    void filtersByAuthenticatedSubjectAndPaginatesEachGroupIndependently(
            String following, String first, String second) throws Exception {
        UUID userId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID popular = insertChannel("Followed popular", 300);
        follow(popular, userId);
        follow(popular, otherUser);
        follow(insertChannel("Followed smaller", 100), userId);
        UUID otherUserChannel = insertChannel("Other user", 400);
        follow(otherUserChannel, otherUser);
        insertChannel("New channel", 200);
        UUID banned = insertChannel("Banned", 1000);
        UUID deleted = insertChannel("Deleted", 1000);
        follow(banned, userId);
        follow(deleted, userId);
        jdbc.update("UPDATE channels SET status = 'BANNED' WHERE id = ?", banned);
        jdbc.update("UPDATE channels SET deleted_at = ? WHERE id = ?", LocalDateTime.now(), deleted);

        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString()))
                        .param("following", following).param("userId", otherUser.toString()).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0]", aMapWithSize(5)))
                .andExpect(jsonPath("$.result.content[0].id")
                        .value((Boolean.parseBoolean(following) ? popular : otherUserChannel).toString()))
                .andExpect(jsonPath("$.result.content[0].name").value(first))
                .andExpect(jsonPath("$.result.page").value(0))
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.totalPages").value(2));

        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString()))
                        .param("following", following).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value(second))
                .andExpect(jsonPath("$.result.totalElements").value(2));

        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString()))
                        .param("following", following).param("page", "5").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(0)))
                .andExpect(jsonPath("$.result.totalElements").value(2));
    }

    @Test
    void omittingFollowingDefaultsToUnfollowedChannelsForAuthenticatedUsers() throws Exception {
        UUID userId = UUID.randomUUID();
        follow(insertChannel("Followed", 100), userId);
        insertChannel("Not followed", 200);

        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].name").value("Not followed"))
                .andExpect(jsonPath("$.result.totalElements").value(1));
    }

    @ParameterizedTest
    @CsvSource({"default,false", "false,false", "default,true", "false,true"})
    void unfollowedListingIncludesOwnChannelOnlyWhenItHasNoCurrentSelfFollow(
            String following, boolean legacySelfFollow) throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ownChannel = insertChannel("My channel", 300);
        UUID otherChannel = insertChannel("Other channel", 200);
        jdbc.update("UPDATE channels SET user_id = ? WHERE id = ?", userId, ownChannel);
        jdbc.update("UPDATE channels SET user_id = ? WHERE id = ?", UUID.randomUUID(), otherChannel);
        if (legacySelfFollow) {
            follow(ownChannel, userId);
        }
        MockHttpServletRequestBuilder request = get(PATH).contextPath("/channel")
                .cookie(accessCookie(userId.toString())).param("page", "0").param("size", "20");
        if (!following.equals("default")) {
            request.param("following", following);
        }

        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(legacySelfFollow ? 1 : 2)))
                .andExpect(jsonPath("$.result.content[0].id")
                        .value((legacySelfFollow ? otherChannel : ownChannel).toString()))
                .andExpect(jsonPath("$.result.totalElements").value(legacySelfFollow ? 1 : 2));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void followedListingRequiresMembershipEvenForOwnChannel(boolean legacySelfFollow) throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ownChannel = insertChannel("My channel", 300);
        UUID otherChannel = insertChannel("Followed channel", 200);
        jdbc.update("UPDATE channels SET user_id = ? WHERE id = ?", userId, ownChannel);
        follow(otherChannel, userId);
        if (legacySelfFollow) {
            follow(ownChannel, userId);
        }

        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString()))
                        .param("following", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(legacySelfFollow ? 2 : 1)))
                .andExpect(jsonPath("$.result.content[0].id")
                        .value((legacySelfFollow ? ownChannel : otherChannel).toString()))
                .andExpect(jsonPath("$.result.totalElements").value(legacySelfFollow ? 2 : 1));
    }

    @Test
    void defaultListingAlsoRequiresAuthentication() throws Exception {
        mvc.perform(get(PATH).contextPath("/channel"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void explicitlyEmptyFollowingIsRejectedByBeanValidation() throws Exception {
        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(UUID.randomUUID().toString()))
                        .param("following", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1004));
    }

    @ParameterizedTest
    @CsvSource({"true,0", "false,2"})
    void usersWithNoFollowsSeeAnEmptyFollowedGroupAndAllUnfollowedChannels(String following, int count) throws Exception {
        UUID userId = UUID.randomUUID();
        follow(insertChannel("Someone else's follow", 100), UUID.randomUUID());
        insertChannel("No followers", 200);

        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString()))
                        .param("following", following))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(count)))
                .andExpect(jsonPath("$.result.totalElements").value(count));
    }

    @ParameterizedTest
    @CsvSource({"true,Gaming followed B,Gaming followed A", "false,Gaming unfollowed B,Gaming unfollowed A"})
    void combinesFollowingWithMultipleCategoriesKeywordAndAscendingFollowerSort(
            String following, String first, String second) throws Exception {
        UUID userId = UUID.randomUUID();
        UUID firstCategory = insertCategory();
        UUID secondCategory = insertCategory();
        UUID followedA = insertChannel("Gaming followed A", 200);
        UUID followedB = insertChannel("Gaming followed B", 100);
        follow(followedA, userId);
        follow(followedB, userId);
        assignCategory(followedA, firstCategory);
        assignCategory(followedA, secondCategory);
        assignCategory(followedB, secondCategory);
        assignCategory(insertChannel("Gaming unfollowed A", 400), firstCategory);
        assignCategory(insertChannel("Gaming unfollowed B", 300), secondCategory);
        UUID differentTopic = insertChannel("Different topic", 500);
        UUID differentCategory = insertChannel("Gaming other category", 600);
        assignCategory(differentTopic, firstCategory);
        assignCategory(differentCategory, insertCategory());
        if (Boolean.parseBoolean(following)) {
            follow(differentTopic, userId);
            follow(differentCategory, userId);
        }

        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString()))
                        .param("following", following).param("categoryIds", firstCategory + "," + secondCategory)
                        .param("keyword", "gaming").param("sortDirection", "ASC").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value(first))
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.totalPages").value(2));

        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString()))
                        .param("following", following).param("categoryIds", firstCategory + "," + secondCategory)
                        .param("keyword", "gaming").param("sortDirection", "ASC").param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value(second))
                .andExpect(jsonPath("$.result.totalElements").value(2));
    }

    @Test
    void followFilteringReflectsCurrentMembershipAfterFollowAndUnfollow() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID channelId = insertChannel("Channel", 100);
        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString()))
                        .param("following", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(0));

        follow(channelId, userId);
        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString()))
                        .param("following", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(1));
        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString()))
                        .param("following", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(0));

        jdbc.update("DELETE FROM channel_followers WHERE channel_id = ? AND user_id = ?", channelId, userId);
        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString()))
                        .param("following", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(0));
        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(userId.toString()))
                        .param("following", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "null"})
    void malformedFollowingIsRejectedByRequestBinding(String following) throws Exception {
        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(UUID.randomUUID().toString())).param("following", following))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1004));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void personalizedListingRequiresAValidUserUuidInTheAuthenticatedSubject(String subject) throws Exception {
        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(subject)).param("following", "true"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "true", "false"})
    void invalidAccessCookieIsRejectedForDefaultAndExplicitFollowingFilters(String following) throws Exception {
        MockHttpServletRequestBuilder request = get(PATH).contextPath("/channel")
                .cookie(new Cookie("access_token", "malformed-token"));
        if (!following.isEmpty()) {
            request.param("following", following);
        }
        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void serviceRejectsFollowingFiltersWithoutAUserIdentity(boolean following) {
        ChannelListRequest request = ChannelListRequest.builder().following(following).build();
        AppException exception = assertThrows(AppException.class, () -> channelService.getChannels(null, request));
        assertEquals(ErrorCode.UNAUTHENTICATED, exception.getErrorCode());
    }

    private UUID insertChannel(String name, long followerCount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channels (id, streamer_id, name, slug, status, follower_count)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                """, id, UUID.randomUUID(), name, id.toString(), followerCount);
        return id;
    }

    private UUID insertCategory() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, name, slug, status) VALUES (?, ?, ?, 'ACTIVE')",
                id, id.toString(), id.toString());
        return id;
    }

    private void assignCategory(UUID channelId, UUID categoryId) {
        jdbc.update("INSERT INTO channel_categories (channel_id, category_id) VALUES (?, ?)", channelId, categoryId);
    }

    private void follow(UUID channelId, UUID userId) {
        jdbc.update("INSERT INTO channel_followers (channel_id, user_id) VALUES (?, ?)", channelId, userId);
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
