package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.client.user.UserChatProfileApiClient;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.ChannelUserBanEntity;
import com.reallive.channelservice.channel.entity.enums.ChannelBanType;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelModeratorRepository;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.repository.ChannelUserBanRepository;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelBanServiceImplTest {
    ChannelRepository channels = mock(ChannelRepository.class);
    ChannelUserBanRepository bans = mock(ChannelUserBanRepository.class);
    UserChatProfileApiClient profiles = mock(UserChatProfileApiClient.class);
    ChannelModeratorRepository moderators = mock(ChannelModeratorRepository.class);
    ChannelBanServiceImpl service = new ChannelBanServiceImpl(channels, bans, profiles, moderators);
    UUID channelId = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID user = UUID.randomUUID();

    @BeforeEach
    void setup() {
        when(channels.findByIdForUpdate(channelId)).thenReturn(Optional.of(ChannelEntity.builder()
                .id(channelId).userId(owner).status(ChannelStatus.ACTIVE).build()));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(owner.toString(), "", "CHANNEL_CHAT_BAN", "CHANNEL_CHAT_UNBAN", "CHANNEL_FULL_BAN", "CHANNEL_FULL_UNBAN", "CHANNEL_LIVE_VIEW_BAN", "CHANNEL_LIVE_VIEW_UNBAN"));
    }

    @AfterEach
    void cleanup() { SecurityContextHolder.clearContext(); }

    @Test
    void ownerCannotBeBanned() {
        assertEquals(ErrorCode.CANNOT_BAN_CHANNEL_OWNER, assertThrows(AppException.class,
                () -> service.banUserFromChannel(owner, channelId, owner, ChannelBanType.CHAT_BAN)).getErrorCode());
    }

    @Test
    void downgradeRequiresUnbanPermissionForCurrentLevel() {
        var ban = ChannelUserBanEntity.builder().banType(ChannelBanType.FULL_BAN).build();
        when(bans.findByChannelIdAndUserId(channelId, user)).thenReturn(Optional.of(ban));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(owner.toString(), "", "CHANNEL_CHAT_BAN"));
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(AppException.class,
                () -> service.banUserFromChannel(owner, channelId, user, ChannelBanType.CHAT_BAN)).getErrorCode());
        verify(bans, never()).saveAndFlush(any());
    }

    @Test
    void downgradeAndUnbanUseCurrentType() {
        var ban = ChannelUserBanEntity.builder().banType(ChannelBanType.FULL_BAN).build();
        when(bans.findByChannelIdAndUserId(channelId, user)).thenReturn(Optional.of(ban));
        assertEquals(ChannelBanType.CHAT_BAN, service.banUserFromChannel(owner, channelId, user, ChannelBanType.CHAT_BAN).banType());
        assertEquals(ChannelBanType.CHAT_BAN, ban.getBanType());
        verifyNoInteractions(profiles);
        assertNull(service.unbanUserFromChannel(owner, channelId, user).banType());
        verify(bans).delete(ban);
    }

    @ParameterizedTest
    @CsvSource({
            "CHAT_BAN, LIVE_VIEW_BAN", "CHAT_BAN, FULL_BAN",
            "LIVE_VIEW_BAN, CHAT_BAN", "LIVE_VIEW_BAN, FULL_BAN",
            "FULL_BAN, CHAT_BAN", "FULL_BAN, LIVE_VIEW_BAN"
    })
    void changesBetweenAllDifferentLevels(ChannelBanType previous, ChannelBanType requested) {
        var ban = ChannelUserBanEntity.builder().banType(previous).build();
        when(bans.findByChannelIdAndUserId(channelId, user)).thenReturn(Optional.of(ban));
        assertEquals(requested, service.banUserFromChannel(owner, channelId, user, requested).banType());
        assertEquals(requested, ban.getBanType());
        verifyNoInteractions(profiles);
    }

    @ParameterizedTest
    @CsvSource({"CHAT_BAN", "LIVE_VIEW_BAN", "FULL_BAN"})
    void removesAnyLevelWithMatchingPermission(ChannelBanType type) {
        var ban = ChannelUserBanEntity.builder().banType(type).build();
        when(bans.findByChannelIdAndUserId(channelId, user)).thenReturn(Optional.of(ban));
        assertNull(service.unbanUserFromChannel(owner, channelId, user).banType());
        verify(bans).delete(ban);
    }

    @Test
    void nonOwnerMustBeModerator() {
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(AppException.class,
                () -> service.banUserFromChannel(user, channelId, UUID.randomUUID(), ChannelBanType.CHAT_BAN)).getErrorCode());
    }
}
