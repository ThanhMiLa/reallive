package com.reallive.channelservice.channel.controller.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
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

    private static final String PATH = "/channel/api/channels";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anonymousRequestReturnsEmptyPageWithDefaultPaginationAndStandardEnvelope() throws Exception {
        mvc.perform(get(PATH).contextPath("/channel"))
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

        mvc.perform(get(PATH).contextPath("/channel").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0]", aMapWithSize(4)))
                .andExpect(jsonPath("$.result.content[0].name").value("Visible"))
                .andExpect(jsonPath("$.result.content[0].bannerUrl").value("https://example.com/banner.png"))
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

        mvc.perform(get(PATH).contextPath("/channel").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(2)))
                .andExpect(jsonPath("$.result.content[0].name").value("Oldest"))
                .andExpect(jsonPath("$.result.content[1].name").value("Middle"))
                .andExpect(jsonPath("$.result.page").value(0))
                .andExpect(jsonPath("$.result.size").value(2))
                .andExpect(jsonPath("$.result.totalElements").value(3))
                .andExpect(jsonPath("$.result.totalPages").value(2));

        mvc.perform(get(PATH).contextPath("/channel").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].name").value("Newest"))
                .andExpect(jsonPath("$.result.page").value(1))
                .andExpect(jsonPath("$.result.totalElements").value(3))
                .andExpect(jsonPath("$.result.totalPages").value(2));

        mvc.perform(get(PATH).contextPath("/channel").param("page", "5").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(0)))
                .andExpect(jsonPath("$.result.page").value(5))
                .andExpect(jsonPath("$.result.totalElements").value(3))
                .andExpect(jsonPath("$.result.totalPages").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ASC", "DESC"})
    void breaksFollowerAndCreationTimeTiesByIdAndPreservesNullImagesAndZeroFollowers(String direction) throws Exception {
        UUID first = insertChannel("First", "ACTIVE", false, CREATED_AT);
        UUID second = insertChannel("Second", "ACTIVE", false, CREATED_AT);
        jdbc.update("UPDATE channels SET id = ? WHERE id = ?",
                UUID.fromString("00000000-0000-0000-0000-000000000001"), first);
        jdbc.update("UPDATE channels SET id = ? WHERE id = ?",
                UUID.fromString("00000000-0000-0000-0000-000000000002"), second);

        mvc.perform(get(PATH).contextPath("/channel").param("sortDirection", direction).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("Second"))
                .andExpect(jsonPath("$.result.content[0]", aMapWithSize(4)))
                .andExpect(jsonPath("$.result.content[0].bannerUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.content[0].avatarUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.content[0].numberOfFollower").value(0));

        mvc.perform(get(PATH).contextPath("/channel").param("sortDirection", direction)
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("First"));
    }

    @ParameterizedTest
    @CsvSource({"ASC,Zero,Popular", "DESC,Popular,Zero"})
    void sortsStoredFollowerCountsInBothDirections(String direction, String first, String last) throws Exception {
        insertChannel("Zero", 0, CREATED_AT.plusDays(2));
        insertChannel("Middle", 200, CREATED_AT.plusDays(1));
        insertChannel("Popular", 3_000_000_000L, CREATED_AT);

        mvc.perform(get(PATH).contextPath("/channel").param("sortDirection", direction))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(3)))
                .andExpect(jsonPath("$.result.content[0].name").value(first))
                .andExpect(jsonPath("$.result.content[1].name").value("Middle"))
                .andExpect(jsonPath("$.result.content[2].name").value(last));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "description", "slug"})
    void keywordMatchesEachSearchableFieldIgnoringCaseAndSurroundingWhitespace(String field) throws Exception {
        UUID matching = insertChannel("Matching", "ACTIVE", false, CREATED_AT);
        insertChannel("Unrelated", "ACTIVE", false, CREATED_AT.plusDays(1));
        jdbc.update("UPDATE channels SET " + field + " = ? WHERE id = ?", "preGaMiNgpost", matching);

        mvc.perform(get(PATH).contextPath("/channel").param("keyword", "  GAMING  ").param("size", "1"))
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

        mvc.perform(get(PATH).contextPath("/channel").param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].name").value("Contains " + keyword))
                .andExpect(jsonPath("$.result.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "\t", "\u2003"})
    void blankKeywordLeavesChannelsUnfiltered(String keyword) throws Exception {
        insertChannel("Visible", "ACTIVE", false, CREATED_AT);
        mvc.perform(get(PATH).contextPath("/channel").param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.totalElements").value(1));
    }

    @Test
    void noKeywordMatchReturnsAnEmptyPage() throws Exception {
        insertChannel("Visible", "ACTIVE", false, CREATED_AT);
        mvc.perform(get(PATH).contextPath("/channel").param("keyword", "unmatched"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(0)))
                .andExpect(jsonPath("$.result.totalElements").value(0))
                .andExpect(jsonPath("$.result.totalPages").value(0));
    }

    @Test
    void validatesKeywordLengthAfterTrimming() throws Exception {
        String keyword = "a".repeat(255);
        UUID channel = insertChannel("Matching", "ACTIVE", false, CREATED_AT);
        jdbc.update("UPDATE channels SET description = ? WHERE id = ?", keyword, channel);

        mvc.perform(get(PATH).contextPath("/channel").param("keyword", "  " + keyword + "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)));
        mvc.perform(get(PATH).contextPath("/channel").param("keyword", keyword + "a"))
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

        mvc.perform(get(PATH).contextPath("/channel").param("categoryIds", selected.toString()).param("size", "1"))
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
            mvc.perform(get(PATH).contextPath("/channel").param("categoryIds", categoryId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.content", hasSize(0)))
                    .andExpect(jsonPath("$.result.totalElements").value(0));
        }
        mvc.perform(get(PATH).contextPath("/channel"))
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

        mvc.perform(get(PATH).contextPath("/channel").param("categoryIds", values).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(2)))
                .andExpect(jsonPath("$.result.content[0].name").value("First only"))
                .andExpect(jsonPath("$.result.content[1].name").value("Both categories"))
                .andExpect(jsonPath("$.result.totalElements").value(3))
                .andExpect(jsonPath("$.result.totalPages").value(2));

        mvc.perform(get(PATH).contextPath("/channel").param("categoryIds", values)
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].name").value("Second only"))
                .andExpect(jsonPath("$.result.totalElements").value(3));

        mvc.perform(get(PATH).contextPath("/channel").param("categoryIds", values)
                        .param("keyword", "only").param("sortDirection", "ASC").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("Second only"))
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.totalPages").value(2));
    }

    @Test
    void inactiveAndMissingIdsDoNotPreventMatchesInSelectedActiveCategories() throws Exception {
        UUID active = insertCategory("ACTIVE");
        UUID inactive = insertCategory("INACTIVE");
        assignCategory(insertChannel("Visible", 100, CREATED_AT), active);
        assignCategory(insertChannel("Inactive category only", 200, CREATED_AT), inactive);

        mvc.perform(get(PATH).contextPath("/channel")
                        .param("categoryIds", active + "," + inactive + "," + UUID.randomUUID()).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].name").value("Visible"))
                .andExpect(jsonPath("$.result.totalElements").value(1));
    }

    @Test
    void emptyCategorySetDisablesCategoryFiltering() throws Exception {
        insertChannel("Uncategorised", 100, CREATED_AT);
        mvc.perform(get(PATH).contextPath("/channel").param("categoryIds", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("Uncategorised"))
                .andExpect(jsonPath("$.result.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"comma", "repeated"})
    void rejectsInvalidOrEmptyCategoryElementsAlongsideValidIds(String format) throws Exception {
        UUID active = insertCategory("ACTIVE");
        for (String invalid : new String[]{"not-a-uuid", ""}) {
            MockHttpServletRequestBuilder request = get(PATH).contextPath("/channel");
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

        mvc.perform(get(PATH).contextPath("/channel").param("categoryIds", categoryId.toString())
                        .param("keyword", "gaming").param("sortDirection", "ASC").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].name").value("Gaming smaller"))
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.totalPages").value(2));

        mvc.perform(get(PATH).contextPath("/channel").param("categoryIds", categoryId.toString())
                        .param("keyword", "gaming").param("sortDirection", "ASC").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("Gaming popular"))
                .andExpect(jsonPath("$.result.totalElements").value(2));

        mvc.perform(get(PATH).contextPath("/channel").param("categoryIds", categoryId.toString())
                        .param("keyword", "gaming").param("page", "5").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(0)))
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.totalPages").value(2));
    }

    @ParameterizedTest
    @CsvSource({"categoryIds,not-a-uuid", "sortDirection,INVALID", "sortDirection,followerCount"})
    void rejectsMalformedFilterAndSortParameters(String parameter, String value) throws Exception {
        mvc.perform(get(PATH).contextPath("/channel").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1004))
                .andExpect(jsonPath("$.result").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "size", "sortDirection"})
    void rejectsExplicitlyEmptyRequiredParameters(String parameter) throws Exception {
        mvc.perform(get(PATH).contextPath("/channel").param(parameter, ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1004));
    }

    @Test
    void defaultPageSizeLimitsResultsToTwenty() throws Exception {
        for (int index = 0; index < 21; index++) {
            insertChannel("Channel " + index, "ACTIVE", false, CREATED_AT.plusDays(index));
        }
        mvc.perform(get(PATH).contextPath("/channel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(20)))
                .andExpect(jsonPath("$.result.totalElements").value(21))
                .andExpect(jsonPath("$.result.totalPages").value(2));
    }

    @Test
    void acceptsMaximumPageSize() throws Exception {
        mvc.perform(get(PATH).contextPath("/channel").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.size").value(100));
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,-1", "size,101", "page,abc", "size,abc",
            "page,2147483648", "size,2147483648", "page,2147483647"})
    void rejectsInvalidPaginationWithStandardBadRequest(String parameter, String value) throws Exception {
        mvc.perform(get(PATH).contextPath("/channel").param(parameter, value))
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
    }

    private UUID insertChannel(String name, String status, boolean deleted, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channels (id, streamer_id, name, slug, status, created_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, UUID.randomUUID(), name, id.toString(), status, createdAt, deleted ? createdAt : null);
        return id;
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
