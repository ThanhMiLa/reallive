package com.reallive.channelservice.channel.controller.internal;

import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.common.adapter.redis.RedisAuthoritiesReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"server.servlet.context-path=/channel", "spring.datasource.url=jdbc:h2:mem:streaming_channel_context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StreamingChannelIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ChannelRepository channels;
    @MockitoBean private RedisAuthoritiesReader authorities;

    @Test
    void returnsActiveChannelIdentityWithoutCookie() throws Exception {
        var channel = channel(ChannelStatus.ACTIVE, UUID.randomUUID(), false);
        mvc.perform(get("/channel/internal/api/channels/by-streamer/{id}/streaming-context", channel.getStreamerId()).contextPath("/channel"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.channelId").value(channel.getId().toString()))
                .andExpect(jsonPath("$.result.streamerId").value(channel.getStreamerId().toString()))
                .andExpect(jsonPath("$.result.ownerUserId").value(channel.getUserId().toString()))
                .andExpect(jsonPath("$.result.status").value("ACTIVE"));
    }

    @Test
    void rejectsInactiveDeletedMissingAndOwnerlessChannels() throws Exception {
        var banned = channel(ChannelStatus.BANNED, UUID.randomUUID(), false);
        var deleted = channel(ChannelStatus.ACTIVE, UUID.randomUUID(), true);
        var ownerless = channel(ChannelStatus.ACTIVE, null, false);
        mvc.perform(get("/channel/internal/api/channels/by-streamer/{id}/streaming-context", banned.getStreamerId()).contextPath("/channel"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/channel/internal/api/channels/by-streamer/{id}/streaming-context", deleted.getStreamerId()).contextPath("/channel"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/channel/internal/api/channels/by-streamer/{id}/streaming-context", ownerless.getStreamerId()).contextPath("/channel"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/channel/internal/api/channels/by-streamer/{id}/streaming-context", UUID.randomUUID()).contextPath("/channel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void keepsUnmappedMethodsOnStreamingContextProtected() throws Exception {
        mvc.perform(post("/channel/internal/api/channels/by-streamer/{id}/streaming-context", UUID.randomUUID()).contextPath("/channel"))
                .andExpect(status().isUnauthorized());
    }

    private ChannelEntity channel(ChannelStatus status, UUID owner, boolean deleted) {
        UUID id = UUID.randomUUID();
        return channels.saveAndFlush(ChannelEntity.builder().streamerId(UUID.randomUUID()).userId(owner)
                .name("Streaming channel").slug(id.toString()).status(status)
                .deletedAt(deleted ? LocalDateTime.now() : null).build());
    }
}
