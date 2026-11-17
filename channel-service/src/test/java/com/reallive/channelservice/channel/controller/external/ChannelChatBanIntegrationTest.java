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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import com.reallive.channelservice.channel.entity.enums.ChannelBanType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.TestingAuthenticationToken;
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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void enforcesOwnershipPermissionsAndRepeatedActions() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID channel = channels.saveAndFlush(ChannelEntity.builder().streamerId(UUID.randomUUID()).userId(owner)
                .name("Ban test").slug("ban-" + UUID.randomUUID()).status(ChannelStatus.ACTIVE).build()).getId();
        when(profiles.profile(any())).thenAnswer(invocation -> new ChatProfile(invocation.getArgument(0), "Viewer", null));
        String path = "/api/channels/" + channel + "/users/" + user;
        var permissions = new SimpleGrantedAuthority[]{new SimpleGrantedAuthority("CHANNEL_CHAT_BAN"), new SimpleGrantedAuthority("CHANNEL_CHAT_UNBAN")};
        mvc.perform(post(path + "/ban").contentType("application/json").content("{}")
                .with(jwt().jwt(token -> token.subject(owner.toString())).authorities(permissions))).andExpect(status().isBadRequest());
        mvc.perform(post(path + "/ban").contentType("application/json").content("{\"banType\":\"INVALID\"}")
                .with(jwt().jwt(token -> token.subject(owner.toString())).authorities(permissions))).andExpect(status().isBadRequest());
        mvc.perform(post(path + "/ban").contentType("application/json").content("{\"banType\":\"CHAT_BAN\"}").with(jwt().jwt(token -> token.subject(owner.toString())))).andExpect(status().isForbidden());
        mvc.perform(post(path + "/ban").contentType("application/json").content("{\"banType\":\"CHAT_BAN\"}").with(jwt().jwt(token -> token.subject(user.toString())).authorities(permissions))).andExpect(status().isForbidden());
        mvc.perform(post("/api/channels/" + channel + "/users/" + owner + "/ban").contentType("application/json").content("{\"banType\":\"CHAT_BAN\"}").with(jwt().jwt(token -> token.subject(owner.toString())).authorities(permissions))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1025));
        mvc.perform(post(path + "/ban").contentType("application/json").content("{\"banType\":\"CHAT_BAN\"}").with(jwt().jwt(token -> token.subject(owner.toString())).authorities(permissions))).andExpect(status().isOk()).andExpect(jsonPath("$.result.banType").value("CHAT_BAN"));
        mvc.perform(post(path + "/ban").contentType("application/json").content("{\"banType\":\"CHAT_BAN\"}").with(jwt().jwt(token -> token.subject(owner.toString())).authorities(permissions))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1023));
        mvc.perform(post(path + "/unban").with(jwt().jwt(token -> token.subject(owner.toString())).authorities(permissions))).andExpect(status().isOk());
        mvc.perform(post(path + "/unban").with(jwt().jwt(token -> token.subject(owner.toString())).authorities(permissions))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1024));
    }
    @ParameterizedTest
    @CsvSource({
            "CHAT_BAN, CHANNEL_CHAT_BAN, CHANNEL_LIVE_VIEW_BAN",
            "LIVE_VIEW_BAN, CHANNEL_LIVE_VIEW_BAN, CHANNEL_FULL_BAN",
            "FULL_BAN, CHANNEL_FULL_BAN, CHANNEL_CHAT_BAN"
    })
    void banRequiresPermissionForRequestedLevel(String banType, String requiredPermission, String otherPermission) throws Exception {
        UUID owner = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID channel = channels.saveAndFlush(ChannelEntity.builder().streamerId(UUID.randomUUID()).userId(owner)
                .name("Permission test").slug("permission-" + UUID.randomUUID()).status(ChannelStatus.ACTIVE).build()).getId();
        when(profiles.profile(any())).thenAnswer(invocation -> new ChatProfile(invocation.getArgument(0), "Viewer", null));
        String path = "/api/channels/" + channel + "/users/" + user + "/ban";
        String body = "{\"banType\":\"" + banType + "\"}";
        mvc.perform(post(path).contentType("application/json").content(body)
                .with(jwt().jwt(token -> token.subject(owner.toString())).authorities(new SimpleGrantedAuthority(otherPermission))))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).contentType("application/json").content(body)
                .with(jwt().jwt(token -> token.subject(owner.toString())).authorities(new SimpleGrantedAuthority(requiredPermission))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.banType").value(banType));
    }

    @Test
    void moderationContextIsScopedToOwnerAndActualPermissions() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID channel = channels.saveAndFlush(ChannelEntity.builder().streamerId(UUID.randomUUID()).userId(owner)
                .name("Context test").slug("context-" + UUID.randomUUID()).status(ChannelStatus.ACTIVE).build()).getId();
        String path = "/api/channels/" + channel + "/moderation-context";
        var permission = new SimpleGrantedAuthority("CHANNEL_CHAT_BAN");
        mvc.perform(get(path).with(jwt().jwt(token -> token.subject(owner.toString())).authorities(permission)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.allowedBanTypes[0]").value("CHAT_BAN"))
                .andExpect(jsonPath("$.result.allowedUnbanTypes").isEmpty()).andExpect(jsonPath("$.result.canViewBans").value(false));
        mvc.perform(get(path).with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString())).authorities(permission)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.allowedBanTypes").isEmpty());
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
                SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(owner.toString(), "", "CHANNEL_CHAT_BAN", "CHANNEL_CHAT_UNBAN"));
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
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(owner.toString(), "", "CHANNEL_CHAT_BAN", "CHANNEL_CHAT_UNBAN"));
        banService.banUserFromChannel(owner, channel, user, ChannelBanType.CHAT_BAN);
        var ready = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> request = () -> {
                ready.await();
                SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(owner.toString(), "", "CHANNEL_CHAT_BAN", "CHANNEL_CHAT_UNBAN"));
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

    @Test
    void assignedModeratorCanChangeAndRemoveBanWithLevelSpecificPermissions() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID moderator = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID channel = channels.saveAndFlush(ChannelEntity.builder().streamerId(UUID.randomUUID()).userId(owner)
                .name("Moderator test").slug("moderator-" + UUID.randomUUID()).status(ChannelStatus.ACTIVE).build()).getId();
        moderators.saveAndFlush(ChannelModeratorEntity.builder().id(new ChannelModeratorId(channel, moderator)).build());
        when(profiles.profile(any())).thenAnswer(invocation -> new ChatProfile(invocation.getArgument(0), "Viewer", null));
        String path = "/api/channels/" + channel + "/users/" + target;
        UUID anotherChannel = channels.saveAndFlush(ChannelEntity.builder().streamerId(UUID.randomUUID()).userId(UUID.randomUUID())
                .name("Other channel").slug("other-" + UUID.randomUUID()).status(ChannelStatus.ACTIVE).build()).getId();
        mvc.perform(post("/api/channels/" + anotherChannel + "/users/" + target + "/ban")
                .contentType("application/json").content("{\"banType\":\"CHAT_BAN\"}")
                .with(jwt().jwt(token -> token.subject(moderator.toString())).authorities(new SimpleGrantedAuthority("CHANNEL_CHAT_BAN"))))
                .andExpect(status().isForbidden());
        var permissions = new SimpleGrantedAuthority[]{new SimpleGrantedAuthority("CHANNEL_CHAT_BAN"),
                new SimpleGrantedAuthority("CHANNEL_CHAT_UNBAN"), new SimpleGrantedAuthority("CHANNEL_FULL_BAN"),
                new SimpleGrantedAuthority("CHANNEL_FULL_UNBAN")};
        mvc.perform(post(path + "/ban").contentType("application/json").content("{\"banType\":\"FULL_BAN\"}")
                .with(jwt().jwt(token -> token.subject(moderator.toString())).authorities(permissions)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.banType").value("FULL_BAN"));
        mvc.perform(post(path + "/ban").contentType("application/json").content("{\"banType\":\"CHAT_BAN\"}")
                .with(jwt().jwt(token -> token.subject(moderator.toString())).authorities(new SimpleGrantedAuthority("CHANNEL_CHAT_BAN"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(path + "/ban").contentType("application/json").content("{\"banType\":\"CHAT_BAN\"}")
                .with(jwt().jwt(token -> token.subject(moderator.toString())).authorities(permissions)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.banType").value("CHAT_BAN"));
        mvc.perform(post(path + "/unban").with(jwt().jwt(token -> token.subject(moderator.toString())).authorities(permissions)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/channels/" + channel + "/users/" + owner + "/ban")
                .contentType("application/json").content("{\"banType\":\"CHAT_BAN\"}")
                .with(jwt().jwt(token -> token.subject(moderator.toString())).authorities(permissions)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(1025));
    }

}
