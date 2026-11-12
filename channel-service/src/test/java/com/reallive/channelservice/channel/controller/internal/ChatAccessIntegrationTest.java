package com.reallive.channelservice.channel.controller.internal;

import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.service.ChatAccessService;
import com.reallive.channelservice.common.adapter.redis.RedisAuthoritiesReader;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:chat_access;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatAccessIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelRepository channels;
    @Autowired ChatAccessService accessService;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoBean RedisAuthoritiesReader authorities;
    UUID channelId;
    UUID userId;

    @BeforeEach
    void prepare() {
        channelId = UUID.randomUUID();
        userId = UUID.randomUUID();
        var channel = channels.saveAndFlush(ChannelEntity.builder().streamerId(UUID.randomUUID())
                .name("Chat test").slug("chat-" + channelId).status(ChannelStatus.ACTIVE).build());
        channelId = channel.getId();
    }

    @Test
    void permitsInternalAccessWithoutAccountOrInternalCredentials() throws Exception {
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.userId").value(userId.toString()));
    }

    @Test
    void resolvesEachBanTypeAndLatestUnbanWithoutClearingOtherBans() throws Exception {
        assertAccess(true, true);
        event("CHAT_BAN", "BAN");
        assertAccess(true, false);
        event("LIVE_VIEW_BAN", "BAN");
        assertAccess(false, false);
        event("LIVE_VIEW_BAN", "UNBAN");
        assertAccess(true, false);
        event("CHAT_BAN", "UNBAN");
        assertAccess(true, true);
        event("FULL_BAN", "BAN");
        event("CHAT_BAN", "UNBAN");
        assertAccess(false, false);
        event("FULL_BAN", "UNBAN");
        assertAccess(true, true);
    }

    @Test
    void inactiveOrDeletedChannelsCannotBeReadOrWritten() throws Exception {
        jdbc.update("UPDATE channels SET status = 'BANNED' WHERE id = ?", channelId);
        assertAccess(false, false);
        jdbc.update("UPDATE channels SET status = 'ACTIVE', deleted_at = CURRENT_TIMESTAMP WHERE id = ?", channelId);
        mvc.perform(post(path())
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isNotFound());
    }

    @Test
    void validatesSingleUserAndReturnsNotFoundForMissingChannels() throws Exception {
        mvc.perform(post(path())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\":[]}"))
                .andExpect(status().isBadRequest());
        for (String invalidBody : new String[]{"{}", "{\"userId\":null}", "{\"userId\":\"invalid\"}"}) {
            mvc.perform(post(path())
                    .contentType(MediaType.APPLICATION_JSON).content(invalidBody)).andExpect(status().isBadRequest());
        }
        channelId = UUID.randomUUID();
        mvc.perform(post(path())
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isNotFound());
    }

    @Test
    void resolvesOnlyTheRequestedUser() throws Exception {
        UUID originalUser = userId;
        userId = UUID.randomUUID();
        event("CHAT_BAN", "BAN");
        assertAccess(true, false);
        userId = originalUser;
        assertAccess(true, true);
    }

    @Test
    void queryCountRemainsConstantAsBanHistoryGrows() {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            entityManagerFactory.getCache().evictAll();
            statistics.clear();
            var initialAccess = accessService.resolve(channelId, userId);
            assertTrue(initialAccess.canSend());
            assertEquals(2L, statistics.getPrepareStatementCount());

            String[] types = {"CHAT_BAN", "LIVE_VIEW_BAN", "FULL_BAN"};
            for (int index = 0; index < 120; index++) {
                event(types[index % types.length], "BAN");
            }

            entityManagerFactory.getCache().evictAll();
            statistics.clear();
            var bannedAccess = accessService.resolve(channelId, userId);
            assertFalse(bannedAccess.canView());
            assertFalse(bannedAccess.canSend());
            assertEquals(2L, statistics.getPrepareStatementCount());
        } finally {
            statistics.setStatisticsEnabled(previouslyEnabled);
            statistics.clear();
        }
    }

    private String path() { return "/internal/api/channels/" + channelId + "/chat-access"; }
    private String body() { return "{\"userId\":\"" + userId + "\"}"; }
    private void event(String type, String action) {
        // Equal timestamps deliberately exercise the event_order tie breaker.
        jdbc.update("INSERT INTO channel_user_bans (id, channel_id, user_id, action, ban_type, performed_by, created_at) VALUES (?, ?, ?, ?, ?, ?, TIMESTAMP '2026-11-11 10:00:00')",
                UUID.randomUUID(), channelId, userId, action, type, UUID.randomUUID());
    }
    private void assertAccess(boolean read, boolean send) throws Exception {
        mvc.perform(post(path())
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.userId").value(userId.toString()))
                .andExpect(jsonPath("$.result.canView").value(read))
                .andExpect(jsonPath("$.result.canRead").value(read))
                .andExpect(jsonPath("$.result.canSend").value(send));
    }
}
