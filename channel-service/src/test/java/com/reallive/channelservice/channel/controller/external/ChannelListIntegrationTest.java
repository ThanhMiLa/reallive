package com.reallive.channelservice.channel.controller.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
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
        "spring.datasource.url=jdbc:h2:mem:channel_list_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
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
    void paginatesNewestChannelsAndRetainsTotalsOnLastAndOutOfRangePages() throws Exception {
        insertChannel("Oldest", "ACTIVE", false, CREATED_AT);
        insertChannel("Middle", "ACTIVE", false, CREATED_AT.plusDays(1));
        insertChannel("Newest", "ACTIVE", false, CREATED_AT.plusDays(2));
        insertChannel("Banned", "BANNED", false, CREATED_AT.plusDays(3));
        insertChannel("Deleted", "ACTIVE", true, CREATED_AT.plusDays(4));

        mvc.perform(get(PATH).contextPath("/channel").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(2)))
                .andExpect(jsonPath("$.result.content[0].name").value("Newest"))
                .andExpect(jsonPath("$.result.content[1].name").value("Middle"))
                .andExpect(jsonPath("$.result.page").value(0))
                .andExpect(jsonPath("$.result.size").value(2))
                .andExpect(jsonPath("$.result.totalElements").value(3))
                .andExpect(jsonPath("$.result.totalPages").value(2));

        mvc.perform(get(PATH).contextPath("/channel").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content", hasSize(1)))
                .andExpect(jsonPath("$.result.content[0].name").value("Oldest"))
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

    @Test
    void breaksCreationTimeTiesByIdAcrossPagesAndPreservesNullImagesAndZeroFollowers() throws Exception {
        UUID first = insertChannel("First", "ACTIVE", false, CREATED_AT);
        UUID second = insertChannel("Second", "ACTIVE", false, CREATED_AT);
        jdbc.update("UPDATE channels SET id = ? WHERE id = ?",
                UUID.fromString("00000000-0000-0000-0000-000000000001"), first);
        jdbc.update("UPDATE channels SET id = ? WHERE id = ?",
                UUID.fromString("00000000-0000-0000-0000-000000000002"), second);

        mvc.perform(get(PATH).contextPath("/channel").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("Second"))
                .andExpect(jsonPath("$.result.content[0]", aMapWithSize(4)))
                .andExpect(jsonPath("$.result.content[0].bannerUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.content[0].avatarUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.content[0].numberOfFollower").value(0));

        mvc.perform(get(PATH).contextPath("/channel").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].name").value("First"));
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
}
