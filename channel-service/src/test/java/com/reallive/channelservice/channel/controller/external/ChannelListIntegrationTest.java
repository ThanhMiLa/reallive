package com.reallive.channelservice.channel.controller.external;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.channelservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.channelservice.client.user.UserCategoryPreferenceResolver;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "server.servlet.context-path=/channel",
        "spring.datasource.url=${channel.test.database-url:jdbc:h2:mem:channel_list_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${channel.test.database-username:sa}",
        "spring.datasource.password=${channel.test.database-password:}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ChannelListIntegrationTest {
    @MockitoBean
    private RedisAuthoritiesReader authAuthoritiesReader;
    @MockitoBean
    private UserCategoryPreferenceResolver interests;

    @BeforeEach
    void setUpAuthAuthorities() {
        Mockito.when(authAuthoritiesReader.read(ArgumentMatchers.any()))
                .thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static final String PATH = "/channel/api/channels";
    private static final UUID VIEWER_ID = UUID.randomUUID();
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void authenticatedRequestReturnsEmptyPageWithDefaultPaginationAndStandardEnvelope() throws Exception {
        mvc.perform(authenticatedListRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Channels retrieved successfully"))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.result.content", hasSize(0)))
                .andExpect(jsonPath("$.result.page").value(0))
                .andExpect(jsonPath("$.result.size").value(20))
                .andExpect(jsonPath("$.result.totalElements").value(0))
                .andExpect(jsonPath("$.result.totalPages").value(0));
    }

    @Test
    void returnsOnlyActiveNonDeletedChannelsAndReadsStoredFollowerCount() throws Exception {
        UUID active = insertChannel("Visible", "ACTIVE", false, CREATED_AT);
        insertChannel("Banned", "BANNED", false, CREATED_AT.plusDays(1));
        insertChannel("Deleted", "ACTIVE", true, CREATED_AT.plusDays(2));
        jdbc.update("UPDATE channels SET banner_url = ?, avatar_url = ?, follower_count = ? WHERE id = ?",
                "https://example.com/banner.png", "https://example.com/avatar.png", 3_000_000_000L, active);

        mvc.perform(authenticatedListRequest().param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0]", aMapWithSize(4)))
                .andExpect(jsonPath("$.result.content[0].id").value(active.toString()))
                .andExpect(jsonPath("$.result.content[0].name").value("Visible"))
                .andExpect(jsonPath("$.result.content[0].bannerUrl").doesNotExist())
                .andExpect(jsonPath("$.result.content[0].avatarUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.result.content[0].numberOfFollower").value(3_000_000_000L))
                .andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.totalPages").value(1));
    }

    @Test
    void paginatesByFollowerCountAndRetainsTotalsOnLastAndOutOfRangePages() throws Exception {
        insertChannel("Oldest", 300, CREATED_AT);
        insertChannel("Middle", 200, CREATED_AT.plusDays(1));
        insertChannel("Newest", 100, CREATED_AT.plusDays(2));
        insertChannel("Banned", "BANNED", false, CREATED_AT.plusDays(3));
        insertChannel("Deleted", "ACTIVE", true, CREATED_AT.plusDays(4));

        mvc.perform(authenticatedListRequest().param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(2)))
                .andExpect(jsonPath("$.result.content[0].name").value("Oldest"))
                .andExpect(jsonPath("$.result.content[1].name").value("Middle"))
                .andExpect(jsonPath("$.result.page").value(0))
                .andExpect(jsonPath("$.result.size").value(2))
                .andExpect(jsonPath("$.result.totalElements").value(3))
                .andExpect(jsonPath("$.result.totalPages").value(2));

        mvc.perform(authenticatedListRequest().param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].name").value("Newest"))
                .andExpect(jsonPath("$.result.page").value(1))
                .andExpect(jsonPath("$.result.totalElements").value(3))
                .andExpect(jsonPath("$.result.totalPages").value(2));

        mvc.perform(authenticatedListRequest().param("page", "5").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(0)))
                .andExpect(jsonPath("$.result.page").value(5))
                .andExpect(jsonPath("$.result.totalElements").value(3))
                .andExpect(jsonPath("$.result.totalPages").value(2));
    }

    @Test
    void breaksFollowerAndCreationTimeTiesByIdAndPreservesNullImagesAndZeroFollowers() throws Exception {
        UUID first = insertChannel("First", "ACTIVE", false, CREATED_AT);
        UUID second = insertChannel("Second", "ACTIVE", false, CREATED_AT);
        jdbc.update("UPDATE channels SET id = ? WHERE id = ?",
                UUID.fromString("00000000-0000-0000-0000-000000000001"), first);
        jdbc.update("UPDATE channels SET id = ? WHERE id = ?",
                UUID.fromString("00000000-0000-0000-0000-000000000002"), second);

        mvc.perform(authenticatedListRequest().param("sortDirection", "DESC").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("Second"))
                .andExpect(jsonPath("$.result.content[0]", aMapWithSize(4)))
                .andExpect(jsonPath("$.result.content[0].id").value("00000000-0000-0000-0000-000000000002"))
                .andExpect(jsonPath("$.result.content[0].bannerUrl").doesNotExist())
                .andExpect(jsonPath("$.result.content[0].avatarUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.content[0].numberOfFollower").value(0));

        mvc.perform(authenticatedListRequest().param("sortDirection", "DESC")
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("First"));
    }

    @Test
    void sortsStoredFollowerCountsDescending() throws Exception {
        insertChannel("Zero", 0, CREATED_AT.plusDays(2));
        insertChannel("Middle", 200, CREATED_AT.plusDays(1));
        insertChannel("Popular", 3_000_000_000L, CREATED_AT);

        mvc.perform(authenticatedListRequest().param("sortDirection", "DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(3)))
                .andExpect(jsonPath("$.result.content[0].name").value("Popular"))
                .andExpect(jsonPath("$.result.content[1].name").value("Middle"))
                .andExpect(jsonPath("$.result.content[2].name").value("Zero"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "description", "slug"})
    void keywordMatchesEachSearchableFieldIgnoringCaseAndSurroundingWhitespace(String field) throws Exception {
        UUID matching = insertChannel("Matching", "ACTIVE", false, CREATED_AT);
        insertChannel("Unrelated", "ACTIVE", false, CREATED_AT.plusDays(1));
        jdbc.update("UPDATE channels SET " + field + " = ? WHERE id = ?", "preGaMiNgpost", matching);

        mvc.perform(authenticatedListRequest().param("keyword", "  GAMING  ").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].name").value(field.equals("name") ? "preGaMiNgpost" : "Matching"))
                .andExpect(jsonPath("$.result.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!", "\\", "50%_!"})
    void treatsLikeWildcardsAndEscapeCharactersAsLiteralSearchText(String keyword) throws Exception {
        insertChannel("Contains " + keyword, "ACTIVE", false, CREATED_AT);
        insertChannel("Contains ordinary text", "ACTIVE", false, CREATED_AT);

        mvc.perform(authenticatedListRequest().param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].name").value("Contains " + keyword))
                .andExpect(jsonPath("$.result.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t", "\u2003"})
    void blankKeywordLeavesChannelsUnfiltered(String keyword) throws Exception {
        insertChannel("Visible", "ACTIVE", false, CREATED_AT);
        mvc.perform(authenticatedListRequest().param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.totalElements").value(1));
    }

    @Test
    void noKeywordMatchReturnsAnEmptyPage() throws Exception {
        insertChannel("Visible", "ACTIVE", false, CREATED_AT);
        mvc.perform(authenticatedListRequest().param("keyword", "unmatched"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(0)))
                .andExpect(jsonPath("$.result.totalElements").value(0))
                .andExpect(jsonPath("$.result.totalPages").value(0));
    }

    @Test
    void validatesKeywordLengthBeforeSearchNormalization() throws Exception {
        String keyword = "a".repeat(255);
        UUID channel = insertChannel("Matching", "ACTIVE", false, CREATED_AT);
        jdbc.update("UPDATE channels SET description = ? WHERE id = ?", keyword, channel);

        mvc.perform(authenticatedListRequest().param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)));
        mvc.perform(authenticatedListRequest().param("keyword", keyword + "a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1004));
        mvc.perform(authenticatedListRequest().param("keyword", "  " + keyword + "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1004));
    }

    @Test
    void categoryFilterReturnsOnlyMembersWithoutDuplicatingMultiCategoryChannels() throws Exception {
        UUID selected = insertCategory("ACTIVE");
        UUID other = insertCategory("ACTIVE");
        UUID member = insertChannel("Member", "ACTIVE", false, CREATED_AT);
        UUID excluded = insertChannel("Other category", "ACTIVE", false, CREATED_AT);
        insertChannel("No category", "ACTIVE", false, CREATED_AT);
        assignCategory(member, selected);
        assignCategory(member, other);
        assignCategory(excluded, other);

        mvc.perform(authenticatedListRequest().param("categoryIds", selected.toString()).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].name").value("Member"))
                .andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.totalPages").value(1));
    }

    @Test
    void missingOrInactiveCategoryReturnsEmptyPageButDoesNotHideUnfilteredChannels() throws Exception {
        UUID inactive = insertCategory("INACTIVE");
        UUID member = insertChannel("Visible without category filter", "ACTIVE", false, CREATED_AT);
        assignCategory(member, inactive);
        for (UUID categoryId : new UUID[]{UUID.randomUUID(), inactive}) {
            mvc.perform(authenticatedListRequest().param("categoryIds", categoryId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.content", hasSize(0)))
                    .andExpect(jsonPath("$.result.totalElements").value(0));
        }
        mvc.perform(authenticatedListRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"comma", "repeated"})
    void acceptsMultipleCategoriesWithAnyMatchAndCountsEachChannelOnceAcrossPages(String format) throws Exception {
        UUID firstCategory = insertCategory("ACTIVE");
        UUID secondCategory = insertCategory("ACTIVE");
        UUID outsideCategory = insertCategory("ACTIVE");
        UUID firstOnly = insertChannel("First only", 300, CREATED_AT);
        UUID secondOnly = insertChannel("Second only", 100, CREATED_AT);
        UUID both = insertChannel("Both categories", 200, CREATED_AT);
        assignCategory(firstOnly, firstCategory);
        assignCategory(secondOnly, secondCategory);
        assignCategory(both, firstCategory);
        assignCategory(both, secondCategory);
        assignCategory(insertChannel("Outside", 500, CREATED_AT), outsideCategory);
        insertChannel("Uncategorised", 600, CREATED_AT);

        String[] values = format.equals("comma")
                ? new String[]{firstCategory + "," + secondCategory + "," + firstCategory}
                : new String[]{firstCategory.toString(), secondCategory.toString(), firstCategory.toString()};

        mvc.perform(authenticatedListRequest().param("categoryIds", values).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(2)))
                .andExpect(jsonPath("$.result.content[0].name").value("First only"))
                .andExpect(jsonPath("$.result.content[1].name").value("Both categories"))
                .andExpect(jsonPath("$.result.totalElements").value(3))
                .andExpect(jsonPath("$.result.totalPages").value(2));

        mvc.perform(authenticatedListRequest().param("categoryIds", values)
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].name").value("Second only"))
                .andExpect(jsonPath("$.result.totalElements").value(3));

        mvc.perform(authenticatedListRequest().param("categoryIds", values)
                        .param("keyword", "only").param("sortDirection", "DESC").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("First only"))
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.totalPages").value(2));
    }

    @Test
    void inactiveAndMissingIdsDoNotPreventMatchesInSelectedActiveCategories() throws Exception {
        UUID active = insertCategory("ACTIVE");
        UUID inactive = insertCategory("INACTIVE");
        assignCategory(insertChannel("Visible", 100, CREATED_AT), active);
        assignCategory(insertChannel("Inactive category only", 200, CREATED_AT), inactive);

        mvc.perform(authenticatedListRequest()
                        .param("categoryIds", active + "," + inactive + "," + UUID.randomUUID()).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].name").value("Visible"))
                .andExpect(jsonPath("$.result.totalElements").value(1));
    }

    @Test
    void rejectsAnExplicitlyEmptyCategorySet() throws Exception {
        insertChannel("Uncategorised", 100, CREATED_AT);
        mvc.perform(authenticatedListRequest().param("categoryIds", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1004));
    }

    @ParameterizedTest
    @ValueSource(strings = {"comma", "repeated"})
    void rejectsInvalidOrEmptyCategoryElementsAlongsideValidIds(String format) throws Exception {
        UUID active = insertCategory("ACTIVE");
        for (String invalid : new String[]{"not-a-uuid", ""}) {
            MockHttpServletRequestBuilder request = authenticatedListRequest();
            String[] values = format.equals("comma")
                    ? new String[]{active + "," + invalid}
                    : new String[]{active.toString(), invalid};
            mvc.perform(request.param("categoryIds", values))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(1004));
        }
    }

    @Test
    void combinesCategoryKeywordAndSortAndCountsOnlyEligibleMatchesAcrossPages() throws Exception {
        UUID categoryId = insertCategory("ACTIVE");
        UUID otherCategory = insertCategory("ACTIVE");
        UUID popular = insertChannel("Gaming popular", 200, CREATED_AT);
        UUID smaller = insertChannel("Gaming smaller", 100, CREATED_AT);
        assignCategory(popular, categoryId);
        assignCategory(popular, otherCategory);
        assignCategory(smaller, categoryId);
        assignCategory(insertChannel("Other topic", 500, CREATED_AT), categoryId);
        assignCategory(insertChannel("Gaming other category", 1000, CREATED_AT), otherCategory);
        assignCategory(insertChannel("Gaming banned", "BANNED", false, CREATED_AT), categoryId);
        assignCategory(insertChannel("Gaming deleted", "ACTIVE", true, CREATED_AT), categoryId);

        mvc.perform(authenticatedListRequest().param("categoryIds", categoryId.toString())
                        .param("keyword", "gaming").param("sortDirection", "DESC").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].name").value("Gaming popular"))
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.totalPages").value(2));

        mvc.perform(authenticatedListRequest().param("categoryIds", categoryId.toString())
                        .param("keyword", "gaming").param("sortDirection", "DESC").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("Gaming smaller"))
                .andExpect(jsonPath("$.result.totalElements").value(2));

        mvc.perform(authenticatedListRequest().param("categoryIds", categoryId.toString())
                        .param("keyword", "gaming").param("page", "5").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(0)))
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.totalPages").value(2));
    }

    @ParameterizedTest
    @CsvSource({"categoryIds,not-a-uuid", "sortDirection,INVALID", "sortDirection,followerCount", "sortDirection,ASC"})
    void rejectsMalformedFilterAndSortParameters(String parameter, String value) throws Exception {
        mvc.perform(authenticatedListRequest().param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1004))
                .andExpect(jsonPath("$.result").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "size", "sortDirection"})
    void rejectsExplicitlyEmptyRequiredParameters(String parameter) throws Exception {
        mvc.perform(authenticatedListRequest().param(parameter, ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1004));
    }

    @Test
    void defaultPageSizeLimitsResultsToTwenty() throws Exception {
        for (int index = 0; index < 21; index++) {
            insertChannel("Channel " + index, "ACTIVE", false, CREATED_AT.plusDays(index));
        }
        mvc.perform(authenticatedListRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(20)))
                .andExpect(jsonPath("$.result.totalElements").value(21))
                .andExpect(jsonPath("$.result.totalPages").value(2));
    }

    @Test
    void acceptsMaximumPageSize() throws Exception {
        mvc.perform(authenticatedListRequest().param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.size").value(100));
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,-1", "size,101", "page,abc", "size,abc",
            "page,2147483648", "size,2147483648", "page,2147483647"})
    void rejectsInvalidPaginationWithStandardBadRequest(String parameter, String value) throws Exception {
        mvc.perform(authenticatedListRequest().param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(1004))
                .andExpect(jsonPath("$.result").value(false));
    }

    @Test
    void publicListDoesNotExposeOtherChannelEndpoints() throws Exception {
        mvc.perform(patch(PATH + "/me").contextPath("/channel"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contextPath("/channel"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/me").contextPath("/channel"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/following").contextPath("/channel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prioritizesInterestsAcrossPagesWithoutFilteringOutOtherChannelsOrDuplicatingMatches() throws Exception {
        UUID firstCategory = insertCategory("ACTIVE");
        UUID secondCategory = insertCategory("ACTIVE");
        UUID inactive = insertCategory("INACTIVE");
        UUID matched = insertChannel("Matching popular", 50, CREATED_AT);
        assignCategory(matched, firstCategory);
        UUID smaller = insertChannel("Matching smaller", 10, CREATED_AT);
        assignCategory(smaller, firstCategory);
        assignCategory(smaller, secondCategory);
        assignCategory(insertChannel("Outside popular", 10_000, CREATED_AT), inactive);
        insertChannel("Outside middle", 500, CREATED_AT);
        insertChannel("Outside smaller", 100, CREATED_AT);
        Mockito.when(interests.getInterestCategoryIds(VIEWER_ID)).thenReturn(Set.of(firstCategory, secondCategory, inactive, UUID.randomUUID()));

        List<List<String>> pages = List.of(List.of("Matching popular", "Matching smaller"),
                List.of("Outside popular", "Outside middle"), List.of("Outside smaller"));
        for (int page = 0; page < pages.size(); page++) {
            mvc.perform(authenticatedListRequest().param("size", "2").param("page", String.valueOf(page)))
                    .andExpect(status().isOk())
                    .andExpect(names(pages.get(page)))
                    .andExpect(jsonPath("$.result.totalElements").value(5))
                    .andExpect(jsonPath("$.result.totalPages").value(3));
        }
        mvc.perform(authenticatedListRequest().param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(names(List.of("Matching popular", "Matching smaller", "Outside popular")));
        mvc.perform(authenticatedListRequest().param("page", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content", hasSize(0)))
                .andExpect(jsonPath("$.result.totalElements").value(5));
        Mockito.verify(interests, Mockito.times(5)).getInterestCategoryIds(VIEWER_ID);
    }

    @Test
    void emptyUnmatchedOrInactiveInterestsKeepAllEligibleChannelsInFollowerOrder() throws Exception {
        UUID inactive = insertCategory("INACTIVE");
        assignCategory(insertChannel("Smaller", 100, CREATED_AT), inactive);
        insertChannel("Popular", 500, CREATED_AT);
        for (Set<UUID> categoryIds : List.of(Set.<UUID>of(), Set.of(UUID.randomUUID()), Set.of(inactive))) {
            Mockito.when(interests.getInterestCategoryIds(VIEWER_ID)).thenReturn(categoryIds);
            mvc.perform(authenticatedListRequest()).andExpect(status().isOk())
                    .andExpect(names(List.of("Popular", "Smaller")))
                    .andExpect(jsonPath("$.result.totalElements").value(2));
        }
    }

    @Test
    void prioritiesFollowTheCurrentUsersLatestPreferences() throws Exception {
        UUID firstCategory = insertCategory("ACTIVE");
        UUID secondCategory = insertCategory("ACTIVE");
        assignCategory(insertChannel("First topic", 10, CREATED_AT), firstCategory);
        assignCategory(insertChannel("Second topic", 100, CREATED_AT), secondCategory);
        UUID other = UUID.randomUUID();
        Mockito.when(interests.getInterestCategoryIds(VIEWER_ID)).thenReturn(Set.of(firstCategory), Set.of(secondCategory));
        Mockito.when(interests.getInterestCategoryIds(other)).thenReturn(Set.of(secondCategory));
        mvc.perform(authenticatedListRequest()).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("First topic"));
        mvc.perform(get(PATH).contextPath("/channel").with(jwt().jwt(token -> token.subject(other.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content[0].name").value("Second topic"));
        mvc.perform(authenticatedListRequest()).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("Second topic"));
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true"})
    void explicitFiltersIgnoreInterestsAndNeverCallUserService(boolean keyword, boolean category) throws Exception {
        UUID selected = insertCategory("ACTIVE");
        UUID outside = insertCategory("ACTIVE");
        assignCategory(insertChannel("Matching popular", 500, CREATED_AT), selected);
        assignCategory(insertChannel("Matching smaller", 100, CREATED_AT), selected);
        assignCategory(insertChannel("Matching outside category", 900, CREATED_AT), outside);
        assignCategory(insertChannel("Different topic", 700, CREATED_AT), selected);
        Mockito.doThrow(new AppException(ErrorCode.USER_SERVICE_UNAVAILABLE))
                .when(interests).getInterestCategoryIds(Mockito.any());
        MockHttpServletRequestBuilder request = authenticatedListRequest();
        if (keyword) {
            request.param("keyword", "Matching");
        }
        if (category) {
            request.param("categoryIds", selected.toString());
        }
        List<String> expected = keyword && category ? List.of("Matching popular", "Matching smaller")
                : keyword ? List.of("Matching outside category", "Matching popular", "Matching smaller")
                : List.of("Different topic", "Matching popular", "Matching smaller");
        mvc.perform(request).andExpect(status().isOk()).andExpect(names(expected))
                .andExpect(jsonPath("$.result.totalElements").value(expected.size()));
        Mockito.verifyNoInteractions(interests);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true"})
    void unmatchedExplicitFiltersNeverFallBackToUnrelatedChannels(boolean keyword, boolean category) throws Exception {
        UUID selected = insertCategory("ACTIVE");
        insertChannel("Unrelated", 1000, CREATED_AT);
        MockHttpServletRequestBuilder request = authenticatedListRequest();
        if (keyword) {
            request.param("keyword", "dsdsadsa");
        }
        if (category) {
            request.param("categoryIds", selected.toString());
        }
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.result.content", hasSize(0)))
                .andExpect(jsonPath("$.result.totalElements").value(0));
        Mockito.verifyNoInteractions(interests);
    }

    @ParameterizedTest
    @ValueSource(strings = {"following", "sortBy", "userId"})
    void ignoresParametersOutsideTheRequestModelWithoutChangingMembershipOrUser(String parameter) throws Exception {
        UUID visible = insertChannel("Visible", 100, CREATED_AT);
        UUID followed = insertChannel("Already followed", 1000, CREATED_AT);
        jdbc.update("INSERT INTO channel_followers (channel_id, user_id) VALUES (?, ?)", followed, VIEWER_ID);
        mvc.perform(authenticatedListRequest().param(parameter, parameter.equals("following") ? "true" : "anything"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.content[0].id").value(visible.toString()));
        Mockito.verify(interests).getInterestCategoryIds(VIEWER_ID);
    }

    @Test
    void blankKeywordUsesInterestPriority() throws Exception {
        UUID category = insertCategory("ACTIVE");
        assignCategory(insertChannel("Matching", 10, CREATED_AT), category);
        insertChannel("Other", 1000, CREATED_AT);
        Mockito.when(interests.getInterestCategoryIds(VIEWER_ID)).thenReturn(Set.of(category));
        mvc.perform(authenticatedListRequest().param("keyword", "  ").param("sortDirection", "DESC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content[0].name").value("Matching"));
        Mockito.verify(interests).getInterestCategoryIds(VIEWER_ID);
    }

    @Test
    void anonymousListReturnsAllActiveChannelsInFollowerOrderWithoutReadingInterests() throws Exception {
        UUID owned = insertChannel("Owned", 500, CREATED_AT);
        jdbc.update("UPDATE channels SET user_id = ? WHERE id = ?", VIEWER_ID, owned);
        UUID followed = insertChannel("Followed", 1000, CREATED_AT);
        jdbc.update("INSERT INTO channel_followers (channel_id, user_id) VALUES (?, ?)", followed, VIEWER_ID);
        insertChannel("Legacy owner", 100, CREATED_AT);
        insertChannel("Banned", "BANNED", false, CREATED_AT);
        insertChannel("Deleted", "ACTIVE", true, CREATED_AT);

        mvc.perform(get(PATH).contextPath("/channel").param("size", "2"))
                .andExpect(status().isOk()).andExpect(names(List.of("Followed", "Owned")))
                .andExpect(jsonPath("$.result.totalElements").value(3))
                .andExpect(jsonPath("$.result.totalPages").value(2));
        mvc.perform(get(PATH).contextPath("/channel").param("size", "2").param("page", "1"))
                .andExpect(status().isOk()).andExpect(names(List.of("Legacy owner")))
                .andExpect(jsonPath("$.result.totalElements").value(3));
        Mockito.verifyNoInteractions(interests, authAuthoritiesReader);
    }

    @Test
    void anonymousListStillCombinesExplicitCategoryAndKeywordFilters() throws Exception {
        UUID category = insertCategory("ACTIVE");
        assignCategory(insertChannel("Gaming popular", 1000, CREATED_AT), category);
        assignCategory(insertChannel("Gaming smaller", 100, CREATED_AT), category);
        assignCategory(insertChannel("Other topic", 2000, CREATED_AT), category);
        insertChannel("Gaming outside category", 3000, CREATED_AT);

        mvc.perform(get(PATH).contextPath("/channel").param("categoryIds", category.toString())
                        .param("keyword", "gaming"))
                .andExpect(status().isOk()).andExpect(names(List.of("Gaming popular", "Gaming smaller")))
                .andExpect(jsonPath("$.result.totalElements").value(2));
        Mockito.verifyNoInteractions(interests, authAuthoritiesReader);
    }

    @Test
    void accessCookieAuthenticatesAndPreservesPersonalization() throws Exception {
        UUID category = insertCategory("ACTIVE");
        assignCategory(insertChannel("Preferred", 10, CREATED_AT), category);
        insertChannel("Popular", 1000, CREATED_AT);
        UUID owned = insertChannel("Owned", 5000, CREATED_AT);
        jdbc.update("UPDATE channels SET user_id = ? WHERE id = ?", VIEWER_ID, owned);
        UUID followed = insertChannel("Followed", 9000, CREATED_AT);
        jdbc.update("INSERT INTO channel_followers (channel_id, user_id) VALUES (?, ?)", followed, VIEWER_ID);
        Mockito.when(interests.getInterestCategoryIds(VIEWER_ID)).thenReturn(Set.of(category));
        Instant now = Instant.now();
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), new JWTClaimsSet.Builder()
                .subject(VIEWER_ID.toString()).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900))).build());
        token.sign(new MACSigner("channel-list-test-secret-".repeat(4)));

        mvc.perform(get(PATH).contextPath("/channel").cookie(new Cookie("access_token", token.serialize())))
                .andExpect(status().isOk()).andExpect(names(List.of("Preferred", "Popular")))
                .andExpect(jsonPath("$.result.totalElements").value(2));
        Mockito.verify(interests).getInterestCategoryIds(VIEWER_ID);
        Mockito.verify(authAuthoritiesReader).read(VIEWER_ID);
    }

    @Test
    void unfollowedListRejectsMalformedAccessCookie() throws Exception {
        mvc.perform(get(PATH).contextPath("/channel").cookie(new Cookie("access_token", "malformed-token")))
                .andExpect(status().isUnauthorized());
        Mockito.verifyNoInteractions(interests);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void unfollowedListRejectsInvalidUserSubjectsBeforeReadingPreferences(String subject) throws Exception {
        mvc.perform(get(PATH).contextPath("/channel").with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));
        Mockito.verifyNoInteractions(interests);
    }

    @Test
    void interestPriorityNeverReintroducesFollowedOwnedBannedOrDeletedChannels() throws Exception {
        UUID category = insertCategory("ACTIVE");
        UUID matching = insertChannel("Matching visible", 100, CREATED_AT);
        assignCategory(matching, category);
        insertChannel("Other visible", 1000, CREATED_AT);
        UUID followed = insertChannel("Already followed", 10_000, CREATED_AT);
        UUID own = insertChannel("Own", 20_000, CREATED_AT);
        UUID legacySelfFollow = insertChannel("Legacy self follow", 30_000, CREATED_AT);
        for (UUID channel : List.of(own, legacySelfFollow)) {
            jdbc.update("UPDATE channels SET user_id = ? WHERE id = ?", VIEWER_ID, channel);
        }
        for (UUID channel : List.of(followed, legacySelfFollow)) {
            jdbc.update("INSERT INTO channel_followers (channel_id, user_id) VALUES (?, ?)", channel, VIEWER_ID);
        }
        UUID banned = insertChannel("Banned preferred", "BANNED", false, CREATED_AT);
        UUID deleted = insertChannel("Deleted preferred", "ACTIVE", true, CREATED_AT);
        for (UUID channel : List.of(followed, own, legacySelfFollow, banned, deleted)) {
            assignCategory(channel, category);
        }
        Mockito.when(interests.getInterestCategoryIds(VIEWER_ID)).thenReturn(Set.of(category));

        mvc.perform(authenticatedListRequest()).andExpect(status().isOk())
                .andExpect(names(List.of("Matching visible", "Other visible")))
                .andExpect(jsonPath("$.result.totalElements").value(2));
    }

    @Test
    void interestGroupsBreakEqualFollowerAndCreationTiesByIdDescending() throws Exception {
        UUID category = insertCategory("ACTIVE");
        UUID lower = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higher = UUID.fromString("00000000-0000-0000-0000-000000000002");
        for (UUID id : List.of(lower, higher)) {
            UUID original = insertChannel(id.toString(), 100, CREATED_AT);
            jdbc.update("UPDATE channels SET id = ? WHERE id = ?", id, original);
            assignCategory(id, category);
        }
        insertChannel("Outside popular", 10_000, CREATED_AT.plusDays(1));
        Mockito.when(interests.getInterestCategoryIds(VIEWER_ID)).thenReturn(Set.of(category));

        mvc.perform(authenticatedListRequest()).andExpect(status().isOk())
                .andExpect(names(List.of(higher.toString(), lower.toString(), "Outside popular")))
                .andExpect(jsonPath("$.result.totalElements").value(3));
    }

    private ResultMatcher names(List<String> expected) {
        return result -> {
            jsonPath("$.result.content", hasSize(expected.size())).match(result);
            for (int index = 0; index < expected.size(); index++) {
                jsonPath("$.result.content[" + index + "].name").value(expected.get(index)).match(result);
            }
        };
    }

    private UUID insertChannel(String name, String status, boolean deleted, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channels (id, streamer_id, name, slug, status, created_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, UUID.randomUUID(), name, id.toString(), status, createdAt, deleted ? createdAt : null);
        return id;
    }

    private MockHttpServletRequestBuilder authenticatedListRequest() {
        return get(PATH).contextPath("/channel")
                .with(jwt().jwt(token -> token.subject(VIEWER_ID.toString())));
    }

    private UUID insertChannel(String name, long followerCount, LocalDateTime createdAt) {
        UUID id = insertChannel(name, "ACTIVE", false, createdAt);
        jdbc.update("UPDATE channels SET follower_count = ? WHERE id = ?", followerCount, id);
        return id;
    }

    private UUID insertCategory(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, name, slug, status) VALUES (?, ?, ?, ?)",
                id, id.toString(), id.toString(), status);
        return id;
    }

    private void assignCategory(UUID channelId, UUID categoryId) {
        jdbc.update("INSERT INTO channel_categories (channel_id, category_id) VALUES (?, ?)", channelId, categoryId);
    }
}
