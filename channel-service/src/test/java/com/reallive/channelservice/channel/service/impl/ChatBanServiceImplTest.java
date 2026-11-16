package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.client.user.UserChatProfileApiClient;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.ChannelUserBanEntity;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.repository.ChannelUserBanRepository;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatBanServiceImplTest {
    ChannelRepository channels = mock(ChannelRepository.class);
    ChannelUserBanRepository bans = mock(ChannelUserBanRepository.class);
    UserChatProfileApiClient profiles = mock(UserChatProfileApiClient.class);
    ChatBanServiceImpl service = new ChatBanServiceImpl(channels, bans, profiles);
    UUID channelId = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID user = UUID.randomUUID();

    @BeforeEach
    void setup() {
        when(channels.findByIdForUpdate(channelId)).thenReturn(Optional.of(ChannelEntity.builder()
                .id(channelId).userId(owner).status(ChannelStatus.ACTIVE).build()));
    }

    @Test
    void ownerCannotBeBanned() {
        var exception = assertThrows(AppException.class, () -> service.ban(owner, channelId, owner));
        assertEquals(ErrorCode.CANNOT_BAN_CHANNEL_OWNER, exception.getErrorCode());
        verifyNoInteractions(bans, profiles);
    }

    @Test
    void higherBanCannotBeOverriddenOrRemoved() {
        when(bans.findByChannelIdAndUserId(channelId, user)).thenReturn(Optional.of(
                ChannelUserBanEntity.builder().banType("FULL_BAN").build()));
        assertEquals(ErrorCode.CHANNEL_CHAT_BAN_BLOCKED_BY_HIGHER_BAN,
                assertThrows(AppException.class, () -> service.ban(owner, channelId, user)).getErrorCode());
        assertEquals(ErrorCode.CHANNEL_CHAT_UNBAN_BLOCKED_BY_HIGHER_BAN,
                assertThrows(AppException.class, () -> service.unban(owner, channelId, user)).getErrorCode());
        verify(bans, never()).delete(any());
    }

    @Test
    void unbanDeletesOnlyExistingChatBan() {
        var ban = ChannelUserBanEntity.builder().banType("CHAT_BAN").build();
        when(bans.findByChannelIdAndUserId(channelId, user)).thenReturn(Optional.of(ban));
        assertNull(service.unban(owner, channelId, user).banType());
        verify(bans).delete(ban);
    }

    @Test
    void duplicateBanAndMissingUnbanAreRejected() {
        when(bans.findByChannelIdAndUserId(channelId, user)).thenReturn(
                Optional.of(ChannelUserBanEntity.builder().banType("CHAT_BAN").build()), Optional.empty());
        assertEquals(ErrorCode.CHANNEL_CHAT_ALREADY_BANNED,
                assertThrows(AppException.class, () -> service.ban(owner, channelId, user)).getErrorCode());
        assertEquals(ErrorCode.CHANNEL_CHAT_NOT_BANNED,
                assertThrows(AppException.class, () -> service.unban(owner, channelId, user)).getErrorCode());
    }

    @Test
    void createsBanWithAuthenticatedActorAndFixedReason() {
        when(bans.findByChannelIdAndUserId(channelId, user)).thenReturn(Optional.empty());
        assertEquals("CHAT_BAN", service.ban(owner, channelId, user).banType());
        var captor = org.mockito.ArgumentCaptor.forClass(ChannelUserBanEntity.class);
        verify(bans).saveAndFlush(captor.capture());
        assertEquals(owner, captor.getValue().getPerformedBy());
        assertEquals(user, captor.getValue().getUserId());
        assertEquals("Banned from channel chat", captor.getValue().getReason());
        verify(profiles).profile(user);
    }

    @Test
    void nonOwnerCannotManageChannel() {
        assertEquals(ErrorCode.ACCESS_DENIED,
                assertThrows(AppException.class, () -> service.ban(user, channelId, UUID.randomUUID())).getErrorCode());
        verifyNoInteractions(bans, profiles);
    }
}
