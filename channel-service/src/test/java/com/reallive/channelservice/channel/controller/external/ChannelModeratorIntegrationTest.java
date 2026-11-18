package com.reallive.channelservice.channel.controller.external;

import com.reallive.channelservice.channel.client.user.UserChatProfileApiClient;
import com.reallive.channelservice.channel.client.user.UserStatusClient;
import com.reallive.channelservice.channel.client.user.UserStatusClient.Status;
import com.reallive.channelservice.channel.client.user.UserStatusClient.StatusResponse;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.ChannelModeratorId;
import com.reallive.channelservice.channel.entity.enums.ChannelBanType;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelModeratorRepository;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.repository.ChannelUserBanRepository;
import com.reallive.channelservice.channel.security.ChannelAuthorization;
import com.reallive.channelservice.channel.service.ChannelBanService;
import com.reallive.channelservice.channel.service.ChannelModeratorService;
import com.reallive.channelservice.common.adapter.redis.RedisAuthoritiesReader;
import com.reallive.channelservice.common.dto.ApiResponse;
import com.reallive.channelservice.common.exception.AppException;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:moderator_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChannelModeratorIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ChannelRepository channels;
    @Autowired ChannelModeratorRepository moderators;
    @Autowired ChannelUserBanRepository bans;
    @Autowired ChannelModeratorService assignments;
    @Autowired ChannelBanService banService;
    @MockitoBean UserStatusClient users;
    @MockitoBean UserChatProfileApiClient profiles;
    @MockitoBean RedisAuthoritiesReader authorities;
    @MockitoSpyBean ChannelAuthorization authorization;

    @Test
    void assignsAndRemovesFromOnlyTheRequestedChannel() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID channel = channel(owner, ChannelStatus.ACTIVE);
        UUID otherOwner = UUID.randomUUID();
        UUID otherChannel = channel(otherOwner, ChannelStatus.ACTIVE);
        UUID target = UUID.randomUUID();
        when(users.getStatus(target)).thenReturn(account(target, Status.ACTIVE));
        assignments.assign(otherOwner, otherChannel, target);
        String path = path(channel, target);

        mvc.perform(post(path).with(streamer(owner))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.channelId").value(channel.toString()))
                .andExpect(jsonPath("$.result.userId").value(target.toString()));
        mvc.perform(post(path).with(streamer(owner))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1028));
        assertTrue(moderators.existsById(new ChannelModeratorId(channel, target)));

        mvc.perform(delete(path).with(streamer(owner))).andExpect(status().isNoContent());
        mvc.perform(delete(path).with(streamer(owner))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1031));
        assertFalse(moderators.existsById(new ChannelModeratorId(channel, target)));
        assertTrue(moderators.existsById(new ChannelModeratorId(otherChannel, target)));
        verify(users, times(2)).getStatus(target);
    }

    @Test
    void checksPermissionOwnershipAndChannelStatusBeforeLookingUpTarget() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID active = channel(owner, ChannelStatus.ACTIVE);
        UUID inactive = channel(owner, ChannelStatus.BANNED);
        String path = path(active, target);
        var withoutPermission = jwt().jwt(token -> token.subject(owner.toString()));

        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(withoutPermission)).andExpect(status().isForbidden());
        mvc.perform(delete(path).with(withoutPermission)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(streamer(UUID.randomUUID()))).andExpect(status().isForbidden());
        mvc.perform(delete(path).with(streamer(UUID.randomUUID()))).andExpect(status().isForbidden());
        mvc.perform(post(path(inactive, target)).with(streamer(owner))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1010));
        mvc.perform(delete(path(inactive, target)).with(streamer(owner))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1010));
        mvc.perform(post(path(UUID.randomUUID(), target)).with(streamer(owner))).andExpect(status().isNotFound());
        mvc.perform(post(path(active, owner)).with(streamer(owner))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1027));
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @EnumSource(ChannelBanType.class)
    void rejectsEveryChannelBanLevel(ChannelBanType type) throws Exception {
        UUID owner = UUID.randomUUID();
        UUID channel = channel(owner, ChannelStatus.ACTIVE);
        UUID target = UUID.randomUUID();
        banService.banUserFromChannel(owner, channel, target, type);

        mvc.perform(post(path(channel, target)).with(streamer(owner))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1029));
        verifyNoInteractions(users);
        assertFalse(moderators.existsById(new ChannelModeratorId(channel, target)));
    }

    @Test
    void rejectsBannedMissingAndUnavailableAccounts() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID channel = channel(owner, ChannelStatus.ACTIVE);
        UUID target = UUID.randomUUID();
        when(users.getStatus(target)).thenReturn(account(target, Status.BANNED))
                .thenThrow(mock(FeignException.NotFound.class))
                .thenThrow(mock(FeignException.ServiceUnavailable.class));
        String path = path(channel, target);

        mvc.perform(post(path).with(streamer(owner))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1030));
        mvc.perform(post(path).with(streamer(owner))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1022));
        mvc.perform(post(path).with(streamer(owner))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(1008));
        assertFalse(moderators.existsById(new ChannelModeratorId(channel, target)));
    }

    @Test
    void concurrentAssignmentAndBanAllowExactlyOneToCommit() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID channel = channel(owner, ChannelStatus.ACTIVE);
        UUID target = UUID.randomUUID();
        when(users.getStatus(target)).thenReturn(account(target, Status.ACTIVE));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var assign = executor.submit(() -> {
                start.await();
                return outcome(() -> assignments.assign(owner, channel, target));
            });
            var ban = executor.submit(() -> {
                start.await();
                return outcome(() -> banService.banUserFromChannel(owner, channel, target, ChannelBanType.CHAT_BAN));
            });
            start.countDown();
            int assignCode = assign.get(5, TimeUnit.SECONDS);
            int banCode = ban.get(5, TimeUnit.SECONDS);
            boolean assigned = assignCode == 0;
            assertEquals(assigned ? 1026 : 0, banCode);
            assertEquals(assigned ? 0 : 1029, assignCode);
            assertEquals(assigned, moderators.existsById(new ChannelModeratorId(channel, target)));
            assertEquals(!assigned, bans.existsByChannelIdAndUserId(channel, target));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rechecksWriteAccessWhenAssignmentIsRevokedAfterPreAuthorize(boolean unban) throws Exception {
        UUID owner = UUID.randomUUID();
        UUID channel = channel(owner, ChannelStatus.ACTIVE);
        UUID moderator = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        when(users.getStatus(moderator)).thenReturn(account(moderator, Status.ACTIVE));
        assignments.assign(owner, channel, moderator);
        if (unban) {
            banService.banUserFromChannel(owner, channel, target, ChannelBanType.CHAT_BAN);
        }
        doAnswer(invocation -> {
            boolean allowed = (boolean) invocation.callRealMethod();
            assertTrue(allowed);
            assignments.remove(owner, channel, moderator);
            return allowed;
        }).when(authorization).canModerate(any(Authentication.class), eq(channel));

        String path = "/api/channels/" + channel + "/users/" + target + (unban ? "/unban" : "/ban");
        var request = post(path).with(jwt().jwt(token -> token.subject(moderator.toString())));
        if (!unban) {
            request.contentType("application/json").content("""
                    {"banType":"CHAT_BAN"}
                    """);
        }
        mvc.perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1002));
        assertEquals(unban, bans.existsByChannelIdAndUserId(channel, target));
    }

    private int outcome(Runnable action) {
        try {
            action.run();
            return 0;
        } catch (AppException exception) {
            return exception.getErrorCode().getCode();
        }
    }

    private UUID channel(UUID owner, ChannelStatus status) {
        return channels.saveAndFlush(ChannelEntity.builder().streamerId(UUID.randomUUID()).userId(owner)
                .name("Moderation test").slug("moderator-" + UUID.randomUUID()).status(status).build()).getId();
    }

    private String path(UUID channel, UUID target) {
        return "/api/channels/" + channel + "/moderators/" + target;
    }

    private ApiResponse<StatusResponse> account(UUID userId, Status status) {
        return ApiResponse.<StatusResponse>builder().code(1000).status(200)
                .result(new StatusResponse(userId, status)).build();
    }

    private JwtRequestPostProcessor streamer(UUID userId) {
        return jwt().jwt(token -> token.subject(userId.toString()))
                .authorities(new SimpleGrantedAuthority("CHANNEL_MODERATOR_MANAGE"));
    }
}
