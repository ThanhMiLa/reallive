package com.reallive.channelservice.channel.controller.external;

import com.reallive.channelservice.channel.client.user.UserChatProfileApiClient;
import com.reallive.channelservice.channel.client.user.UserChatProfileClient.ChatProfile;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.ChannelModeratorEntity;
import com.reallive.channelservice.channel.entity.ChannelModeratorId;
import com.reallive.channelservice.channel.repository.ChannelModeratorRepository;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.service.ChannelBanService;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.adapter.redis.RedisAuthoritiesReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import com.reallive.channelservice.channel.entity.enums.ChannelBanType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:chat_ban_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChannelChatBanIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ChannelRepository channels;
    @Autowired ChannelModeratorRepository moderators;
    @Autowired ChannelBanService banService;
    @MockitoBean RedisAuthoritiesReader authorities;
    @MockitoBean UserChatProfileApiClient profiles;

    @Test
    void ownerCanManageBansWithoutGlobalPermissions() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID channel = createChannel(owner, ChannelStatus.ACTIVE);
        when(profiles.profile(user)).thenReturn(new ChatProfile(user, "Viewer", null));
        var ownerJwt = jwt().jwt(token -> token.subject(owner.toString()));
        String path = "/api/channels/" + channel + "/users/" + user;

        mvc.perform(post(path + "/ban").contentType("application/json").content("{}").with(ownerJwt))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path + "/ban").contentType("application/json").content("{\"banType\":\"INVALID\"}").with(ownerJwt))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/channels/" + channel + "/users/" + owner + "/ban")
                .contentType("application/json").content(banRequest(ChannelBanType.CHAT_BAN)).with(ownerJwt))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1025));
        mvc.perform(post(path + "/ban").contentType("application/json").content(banRequest(ChannelBanType.CHAT_BAN))
                .with(ownerJwt)).andExpect(status().isOk());
        mvc.perform(get(path + "/ban").with(ownerJwt)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.banType").value("CHAT_BAN"))
                .andExpect(jsonPath("$.result.allowedBanTypes.length()").value(2))
                .andExpect(jsonPath("$.result.canUnban").value(true));
        mvc.perform(post(path + "/ban").contentType("application/json").content(banRequest(ChannelBanType.CHAT_BAN))
                .with(ownerJwt)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1023));
        mvc.perform(post(path + "/unban").with(ownerJwt)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.canUnban").value(false));
        mvc.perform(post(path + "/unban").with(ownerJwt)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1024));
    }

    @Test
    void ownerCanBanModeratorOnlyAfterAssignmentIsRemovedFromThatChannel() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID channel = createChannel(owner, ChannelStatus.ACTIVE);
        UUID otherChannel = createChannel(UUID.randomUUID(), ChannelStatus.ACTIVE);
        UUID target = UUID.randomUUID();
        var assignment = new ChannelModeratorId(channel, target);
        moderators.saveAndFlush(ChannelModeratorEntity.builder().id(assignment).build());
        moderators.saveAndFlush(ChannelModeratorEntity.builder().id(new ChannelModeratorId(otherChannel, target)).build());
        var ownerJwt = jwt().jwt(token -> token.subject(owner.toString()));
        String path = "/api/channels/" + channel + "/users/" + target + "/ban";
        String request = banRequest(ChannelBanType.FULL_BAN);

        mvc.perform(post(path).contentType("application/json").content(request).with(ownerJwt))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1026));

        moderators.deleteById(assignment);
        when(profiles.profile(target)).thenReturn(new ChatProfile(target, "Viewer", null));

        mvc.perform(post(path).contentType("application/json").content(request).with(ownerJwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.banType").value("FULL_BAN"));
    }

    @ParameterizedTest
    @EnumSource(ChannelBanType.class)
    void assignedModeratorCanSetChangeAndRemoveEveryLevel(ChannelBanType initialType) throws Exception {
        UUID channel = createChannel(UUID.randomUUID(), ChannelStatus.ACTIVE);
        UUID moderator = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        moderators.saveAndFlush(ChannelModeratorEntity.builder().id(new ChannelModeratorId(channel, moderator)).build());
        when(profiles.profile(target)).thenReturn(new ChatProfile(target, "Viewer", null));
        var moderatorJwt = jwt().jwt(token -> token.subject(moderator.toString()));
        String path = "/api/channels/" + channel + "/users/" + target;

        mvc.perform(post(path + "/ban").contentType("application/json").content(banRequest(initialType))
                .with(moderatorJwt)).andExpect(status().isOk());
        mvc.perform(get("/api/channels/" + channel + "/bans").with(moderatorJwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content[0].banType").value(initialType.name()));
        ChannelBanType otherType = initialType == ChannelBanType.FULL_BAN ? ChannelBanType.CHAT_BAN : ChannelBanType.FULL_BAN;
        mvc.perform(post(path + "/ban").contentType("application/json").content(banRequest(otherType))
                .with(moderatorJwt)).andExpect(status().isOk()).andExpect(jsonPath("$.result.banType").value(otherType.name()));
        mvc.perform(get(path + "/ban").with(moderatorJwt)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.banType").value(otherType.name()));
        mvc.perform(post(path + "/unban").with(moderatorJwt)).andExpect(status().isOk());
    }

    @Test
    void rejectsModeratorFromAnotherChannelEvenWithLegacyAuthorities() throws Exception {
        UUID channel = createChannel(UUID.randomUUID(), ChannelStatus.ACTIVE);
        UUID otherChannel = createChannel(UUID.randomUUID(), ChannelStatus.ACTIVE);
        UUID moderator = UUID.randomUUID();
        moderators.saveAndFlush(ChannelModeratorEntity.builder().id(new ChannelModeratorId(channel, moderator)).build());
        var moderatorJwt = jwt().jwt(token -> token.subject(moderator.toString())).authorities(
                new SimpleGrantedAuthority("CHANNEL_CHAT_BAN"), new SimpleGrantedAuthority("CHANNEL_CHAT_UNBAN"),
                new SimpleGrantedAuthority("CHANNEL_BAN_VIEW"));
        String path = "/api/channels/" + otherChannel + "/users/" + moderator;

        mvc.perform(post(path + "/ban").contentType("application/json").content(banRequest(ChannelBanType.CHAT_BAN))
                .with(moderatorJwt)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        mvc.perform(post(path + "/unban").with(moderatorJwt)).andExpect(status().isForbidden());
        mvc.perform(get(path + "/ban").with(moderatorJwt)).andExpect(status().isForbidden());
        mvc.perform(get("/api/channels/" + otherChannel + "/bans").with(moderatorJwt)).andExpect(status().isForbidden());
        mvc.perform(get("/api/channels/" + otherChannel + "/bans")).andExpect(status().isUnauthorized());
    }

    @Test
    void moderationContextReportsAllLevelsForManagersAndNoneForViewers() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID channel = createChannel(owner, ChannelStatus.ACTIVE);
        UUID moderator = UUID.randomUUID();
        moderators.saveAndFlush(ChannelModeratorEntity.builder().id(new ChannelModeratorId(channel, moderator)).build());
        String path = "/api/channels/" + channel + "/moderation-context";

        for (UUID manager : java.util.List.of(owner, moderator)) {
            mvc.perform(get(path).with(jwt().jwt(token -> token.subject(manager.toString()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.result.ownerUserId").value(owner.toString()))
                    .andExpect(jsonPath("$.result.allowedBanTypes.length()").value(3))
                    .andExpect(jsonPath("$.result.allowedUnbanTypes.length()").value(3))
                    .andExpect(jsonPath("$.result.canViewBans").value(true));
        }
        mvc.perform(get(path).with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.allowedBanTypes").isEmpty())
                .andExpect(jsonPath("$.result.allowedUnbanTypes").isEmpty())
                .andExpect(jsonPath("$.result.canViewBans").value(false));
    }

    @Test
    void preservesErrorsForMissingInactiveAndOwnerlessChannels() throws Exception {
        UUID owner = UUID.randomUUID();
        var ownerJwt = jwt().jwt(token -> token.subject(owner.toString()));

        mvc.perform(get("/api/channels/" + UUID.randomUUID() + "/bans").with(ownerJwt))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1009));
        mvc.perform(get("/api/channels/" + createChannel(owner, ChannelStatus.BANNED) + "/bans").with(ownerJwt))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1010));
        mvc.perform(get("/api/channels/" + createChannel(null, ChannelStatus.ACTIVE) + "/bans").with(ownerJwt))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void simultaneousBansAllowOnlyOneMutation() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID channel = channels.saveAndFlush(ChannelEntity.builder().streamerId(UUID.randomUUID()).userId(owner)
                .name("Concurrent ban test").slug("ban-" + UUID.randomUUID()).status(ChannelStatus.ACTIVE).build()).getId();
        when(profiles.profile(any())).thenAnswer(invocation -> new ChatProfile(invocation.getArgument(0), "Viewer", null));
        var ready = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> request = () -> {
                ready.await();
                try {
                    banService.banUserFromChannel(owner, channel, user, ChannelBanType.CHAT_BAN);
                    return 0;
                } catch (AppException exception) {
                    return exception.getErrorCode().getCode();
                }
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            ready.countDown();
            org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of(0, 1023),
                    java.util.Set.of(first.get(), second.get()));
        }
    }

    @Test
    void simultaneousUnbansAllowOnlyOneMutation() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID channel = channels.saveAndFlush(ChannelEntity.builder().streamerId(UUID.randomUUID()).userId(owner)
                .name("Concurrent unban test").slug("ban-" + UUID.randomUUID()).status(ChannelStatus.ACTIVE).build()).getId();
        when(profiles.profile(any())).thenAnswer(invocation -> new ChatProfile(invocation.getArgument(0), "Viewer", null));
        banService.banUserFromChannel(owner, channel, user, ChannelBanType.CHAT_BAN);
        var ready = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> request = () -> {
                ready.await();
                try {
                    banService.unbanUserFromChannel(owner, channel, user);
                    return 0;
                } catch (AppException exception) {
                    return exception.getErrorCode().getCode();
                }
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            ready.countDown();
            org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of(0, 1024),
                    java.util.Set.of(first.get(), second.get()));
        }
    }

    private UUID createChannel(UUID owner, ChannelStatus channelStatus) {
        return channels.saveAndFlush(ChannelEntity.builder().streamerId(UUID.randomUUID()).userId(owner)
                .name("Moderation test").slug("moderation-" + UUID.randomUUID()).status(channelStatus).build()).getId();
    }

    private String banRequest(ChannelBanType type) {
        return "{\"banType\":\"%s\"}".formatted(type.name());
    }
}
