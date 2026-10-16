package com.reallive.channelservice.channel.service;

import com.reallive.channelservice.channel.entity.ChannelEntity;
import com.reallive.channelservice.channel.entity.enums.ChannelStatus;
import com.reallive.channelservice.channel.repository.ChannelFollowerRepository;
import com.reallive.channelservice.channel.repository.ChannelRepository;
import com.reallive.channelservice.channel.service.impl.ChannelFollowServiceImpl;
import com.reallive.channelservice.common.exception.AppException;
import com.reallive.channelservice.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelFollowServiceTest {

    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private ChannelFollowerRepository channelFollowerRepository;
    @InjectMocks
    private ChannelFollowServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID channelId = UUID.randomUUID();

    @ParameterizedTest
    @EnumSource(ChannelStatus.class)
    void ownerCannotFollowOwnChannelRegardlessOfStatus(ChannelStatus status) {
        givenChannel(userId, status);

        AppException exception = assertThrows(AppException.class, () -> service.followChannel(userId, channelId));

        assertEquals(ErrorCode.CANNOT_FOLLOW_OWN_CHANNEL, exception.getErrorCode());
        verifyNoInteractions(channelFollowerRepository);
    }

    @Test
    void anotherUserCanFollowAnActiveChannelWithoutChangingItsCounter() {
        ChannelEntity channel = givenChannel(UUID.randomUUID(), ChannelStatus.ACTIVE);
        when(channelFollowerRepository.insertFollowIfAbsent(channelId, userId)).thenReturn(1);

        assertTrue(service.followChannel(userId, channelId));

        assertEquals(123L, channel.getFollowerCount());
        verify(channelRepository).findById(channelId);
        verify(channelFollowerRepository).insertFollowIfAbsent(channelId, userId);
        verifyNoMoreInteractions(channelRepository, channelFollowerRepository);
    }

    @Test
    void legacyChannelWithoutAnOwnerCanBeFollowedWithoutANullPointerException() {
        givenChannel(null, ChannelStatus.ACTIVE);
        when(channelFollowerRepository.insertFollowIfAbsent(channelId, userId)).thenReturn(1);

        assertTrue(service.followChannel(userId, channelId));

        verify(channelFollowerRepository).insertFollowIfAbsent(channelId, userId);
    }

    @Test
    void anotherUserCannotFollowABannedChannel() {
        givenChannel(UUID.randomUUID(), ChannelStatus.BANNED);

        AppException exception = assertThrows(AppException.class, () -> service.followChannel(userId, channelId));

        assertEquals(ErrorCode.CHANNEL_NOT_ACTIVE, exception.getErrorCode());
        verifyNoInteractions(channelFollowerRepository);
    }

    @Test
    void anotherUsersDuplicateFollowKeepsTheExistingBusinessError() {
        givenChannel(UUID.randomUUID(), ChannelStatus.ACTIVE);
        when(channelFollowerRepository.insertFollowIfAbsent(channelId, userId)).thenReturn(0);

        AppException exception = assertThrows(AppException.class, () -> service.followChannel(userId, channelId));

        assertEquals(ErrorCode.CHANNEL_ALREADY_FOLLOWED, exception.getErrorCode());
    }

    @ParameterizedTest
    @EnumSource(ChannelStatus.class)
    void ownerCanRemoveALegacySelfFollowRegardlessOfStatus(ChannelStatus status) {
        ChannelEntity channel = givenChannel(userId, status);
        when(channelFollowerRepository.deleteFollow(channelId, userId)).thenReturn(1);

        assertTrue(service.unfollowChannel(userId, channelId));

        assertEquals(123L, channel.getFollowerCount());
        verify(channelFollowerRepository).deleteFollow(channelId, userId);
        verifyNoMoreInteractions(channelFollowerRepository);
    }

    @Test
    void ownerWithoutALegacySelfFollowReceivesTheNotFollowedError() {
        givenChannel(userId, ChannelStatus.ACTIVE);
        when(channelFollowerRepository.deleteFollow(channelId, userId)).thenReturn(0);

        AppException exception = assertThrows(AppException.class, () -> service.unfollowChannel(userId, channelId));

        assertEquals(ErrorCode.CHANNEL_NOT_FOLLOWED, exception.getErrorCode());
    }

    private ChannelEntity givenChannel(UUID ownerId, ChannelStatus status) {
        ChannelEntity channel = ChannelEntity.builder()
                .id(channelId)
                .userId(ownerId)
                .status(status)
                .followerCount(123L)
                .build();
        when(channelRepository.findById(channelId)).thenReturn(Optional.of(channel));
        return channel;
    }
}
