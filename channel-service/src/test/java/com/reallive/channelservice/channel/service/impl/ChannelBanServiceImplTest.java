package com.reallive.channelservice.channel.service.impl;

import com.reallive.channelservice.channel.client.user.UserChatProfileApiClient;
import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.ChannelUserBanEntity;
import com.reallive.channelservice.channel.entity.enums.ChannelBanType;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.repository.ChannelUserBanRepository;
import com.reallive.channelservice.channel.security.ChannelAuthorization;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelBanServiceImplTest {
    ChannelRepository channels = mock(ChannelRepository.class);
    ChannelUserBanRepository bans = mock(ChannelUserBanRepository.class);
    UserChatProfileApiClient profiles = mock(UserChatProfileApiClient.class);
    ChannelBanServiceImpl service = new ChannelBanServiceImpl(channels, bans, profiles, mock(ChannelAuthorization.class));
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
        assertEquals(ErrorCode.CANNOT_BAN_CHANNEL_OWNER, assertThrows(AppException.class,
                () -> service.banUserFromChannel(owner, channelId, owner, ChannelBanType.CHAT_BAN)).getErrorCode());
    }

    @Test
    void downgradeAndUnbanUseCurrentType() {
        var ban = ChannelUserBanEntity.builder().banType(ChannelBanType.FULL_BAN).build();
        when(bans.findByChannelIdAndUserId(channelId, user)).thenReturn(Optional.of(ban));
        assertEquals(ChannelBanType.CHAT_BAN, service.banUserFromChannel(owner, channelId, user, ChannelBanType.CHAT_BAN).banType());
        assertEquals(ChannelBanType.CHAT_BAN, ban.getBanType());
        verifyNoInteractions(profiles);
        assertNull(service.unbanUserFromChannel(channelId, user).banType());
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
    void removesAnyLevel(ChannelBanType type) {
        var ban = ChannelUserBanEntity.builder().banType(type).build();
        when(bans.findByChannelIdAndUserId(channelId, user)).thenReturn(Optional.of(ban));
        assertNull(service.unbanUserFromChannel(channelId, user).banType());
        verify(bans).delete(ban);
    }

}
