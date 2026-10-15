package com.reallive.userservice.streamer.service.impl;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.streamer.entity.StreamerEntity;
import com.reallive.userservice.streamer.entity.StreamerStatus;
import com.reallive.userservice.streamer.repository.StreamerRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StreamerServiceImplTest {

    private final StreamerRepository streamerRepository = mock(StreamerRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final StreamerServiceImpl service = new StreamerServiceImpl(streamerRepository, userRepository);

    @Test
    void returnsStreamerIdWhenUserAndStreamerAreActive() {
        UUID userId = UUID.randomUUID();
        UUID streamerId = UUID.randomUUID();
        UserEntity user = activeUser(userId);
        StreamerEntity streamer = StreamerEntity.builder()
                .id(streamerId)
                .user(user)
                .status(StreamerStatus.ACTIVE)
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(streamerRepository.findByUserId(userId)).thenReturn(Optional.of(streamer));

        UUID result = service.getActiveStreamerIdByUserId(userId);

        assertEquals(streamerId, result);
        verify(userRepository).findById(userId);
        verify(streamerRepository).findByUserId(userId);
    }

    @Test
    void throwsUserNotFoundWhenUserDoesNotExist() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> service.getActiveStreamerIdByUserId(userId));

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(streamerRepository);
    }

    @Test
    void rejectsInactiveUserBeforeLookingUpStreamer() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId)
                .status(UserStatus.BANNED)
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        AppException exception = assertThrows(AppException.class, () -> service.getActiveStreamerIdByUserId(userId));

        assertEquals(ErrorCode.USER_NOT_ACTIVE, exception.getErrorCode());
        verify(streamerRepository, never()).findByUserId(userId);
    }

    @Test
    void throwsStreamerNotFoundWhenActiveUserHasNoStreamer() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));
        when(streamerRepository.findByUserId(userId)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> service.getActiveStreamerIdByUserId(userId));

        assertEquals(ErrorCode.STREAMER_NOT_FOUND, exception.getErrorCode());
        verify(streamerRepository).findByUserId(userId);
    }

    @Test
    void throwsStreamerNotActiveWhenStreamerIsInactive() {
        UUID userId = UUID.randomUUID();
        UserEntity user = activeUser(userId);
        StreamerEntity streamer = StreamerEntity.builder()
                .id(UUID.randomUUID())
                .user(user)
                .status(StreamerStatus.SUSPENDED)
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(streamerRepository.findByUserId(userId)).thenReturn(Optional.of(streamer));

        AppException exception = assertThrows(AppException.class, () -> service.getActiveStreamerIdByUserId(userId));

        assertEquals(ErrorCode.STREAMER_NOT_ACTIVE, exception.getErrorCode());
        verify(streamerRepository).findByUserId(userId);
    }

    private static UserEntity activeUser(UUID userId) {
        return UserEntity.builder()
                .id(userId)
                .status(UserStatus.ACTIVE)
                .build();
    }
}
