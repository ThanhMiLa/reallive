package com.reallive.userservice.streamer.service.impl;

import com.reallive.userservice.auth.entity.RoleEntity;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.auth.service.AuthStateSyncService;
import com.reallive.userservice.client.channel.ChannelClientResolver;
import com.reallive.userservice.client.channel.CreateChannelRequest;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.streamer.entity.StreamerEntity;
import com.reallive.userservice.streamer.entity.StreamerStatus;
import com.reallive.userservice.streamer.repository.StreamerRepository;
import com.reallive.userservice.user.entity.ProfileEntity;
import com.reallive.userservice.user.repository.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StreamerRegistrationServiceImplTest {

    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final StreamerRepository streamers = mock(StreamerRepository.class);
    private final ChannelClientResolver channel = mock(ChannelClientResolver.class);
    private final ProfileRepository profiles = mock(ProfileRepository.class);
    private final AuthStateSyncService sync = mock(AuthStateSyncService.class);
    private final StreamerRegistrationServiceImpl service = new StreamerRegistrationServiceImpl(users, sync, roles, streamers, channel, profiles);
    private final RoleEntity streamerRole = role("STREAMER");
    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).emailVerified(true)
                .roles(new HashSet<>(Set.of(role("USER"), role("MODERATOR")))).build();
        when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(roles.findByName("STREAMER")).thenReturn(Optional.of(streamerRole));
        when(streamers.save(any())).thenAnswer(invocation -> {
            StreamerEntity streamer = invocation.getArgument(0);
            streamer.setId(UUID.randomUUID());
            return streamer;
        });
        when(profiles.findByUserId(user.getId())).thenReturn(Optional.of(ProfileEntity.builder()
                .user(user).displayName("Test streamer").avatarUrl("https://images.example.com/avatar.png").build()));
        when(channel.createChannel(any())).thenReturn(true);
    }

    @Test
    void locksAndValidatesBeforeAddingRoleAndPersistingActiveStreamer() {
        UUID streamerId = UUID.randomUUID();
        doAnswer(invocation -> {
            StreamerEntity streamer = invocation.getArgument(0);
            streamer.setId(streamerId);
            return streamer;
        }).when(streamers).save(any());

        var response = service.becomeStreamer(user.getId());

        var captured = ArgumentCaptor.forClass(StreamerEntity.class);
        var order = inOrder(users, roles, streamers);
        order.verify(users).findByIdForUpdate(user.getId());
        order.verify(streamers).existsByUserId(user.getId());
        order.verify(roles).findByName("STREAMER");
        verify(sync).changed(user);
        order.verify(streamers).save(captured.capture());
        assertSame(user, captured.getValue().getUser());
        assertEquals(StreamerStatus.ACTIVE, captured.getValue().getStatus());
        assertNotNull(captured.getValue().getVerifiedAt());
        assertEquals(streamerId, response.getStreamerId());
        assertEquals(user.getId(), response.getUserId());
        assertEquals(StreamerStatus.ACTIVE, response.getStatus());
        assertEquals(Set.of("USER", "MODERATOR", "STREAMER"), response.getRoles());
        var channelRequest = ArgumentCaptor.forClass(CreateChannelRequest.class);
        verify(channel).createChannel(channelRequest.capture());
        assertEquals(streamerId, channelRequest.getValue().getStreamerId());
        assertEquals("Test streamer", channelRequest.getValue().getName());
        assertEquals("https://images.example.com/avatar.png", channelRequest.getValue().getAvatarUrl());
    }

    @Test
    void propagatesResolverFailureWithoutReplacingIt() {
        AppException failure = new AppException(ErrorCode.CHANNEL_SERVICE_UNAVAILABLE);
        when(channel.createChannel(any())).thenThrow(failure);

        assertSame(failure, assertThrows(AppException.class, () -> service.becomeStreamer(user.getId())));
        verify(channel).createChannel(any(CreateChannelRequest.class));
    }

    @Test
    void missingProfileKeepsItsErrorCodeAndDoesNotCallChannel() {
        when(profiles.findByUserId(user.getId())).thenReturn(Optional.empty());

        assertEquals(ErrorCode.PROFILE_NOT_FOUND,
                assertThrows(AppException.class, () -> service.becomeStreamer(user.getId())).getErrorCode());
        verifyNoInteractions(channel);
    }

    @Test
    void usesSavedStreamerIdAndKeepsMissingAvatarNull() {
        UUID streamerId = UUID.randomUUID();
        StreamerEntity saved = StreamerEntity.builder().id(streamerId).user(user).status(StreamerStatus.ACTIVE).build();
        doAnswer(invocation -> saved).when(streamers).save(any());
        when(profiles.findByUserId(user.getId())).thenReturn(Optional.of(ProfileEntity.builder()
                .user(user).displayName("Kênh của tôi").build()));

        var response = service.becomeStreamer(user.getId());

        var request = ArgumentCaptor.forClass(CreateChannelRequest.class);
        verify(channel).createChannel(request.capture());
        assertEquals(streamerId, response.getStreamerId());
        assertEquals(streamerId, request.getValue().getStreamerId());
        assertEquals("Kênh của tôi", request.getValue().getName());
        assertNull(request.getValue().getAvatarUrl());
    }

    @Test
    void profileDatabaseFailureIsNotTranslatedIntoChannelServiceError() {
        var failure = new DataAccessResourceFailureException("Profile database unavailable");
        when(profiles.findByUserId(user.getId())).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.becomeStreamer(user.getId())));
        verifyNoInteractions(channel);
    }

    @ParameterizedTest
    @CsvSource({"missing-user, USER_NOT_FOUND", "banned, USER_NOT_ACTIVE", "unverified, EMAIL_NOT_VERIFIED",
            "streamer-role, ALREADY_STREAMER", "streamer-record, ALREADY_STREAMER", "missing-role, STREAMER_ROLE_NOT_FOUND"})
    void rejectedRegistrationDoesNotMutateUserOrPersistAnything(String condition, ErrorCode expected) {
        switch (condition) {
            case "missing-user" -> when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.empty());
            case "banned" -> user.setStatus(UserStatus.BANNED);
            case "unverified" -> user.setEmailVerified(false);
            case "streamer-role" -> user.getRoles().add(streamerRole);
            case "streamer-record" -> when(streamers.existsByUserId(user.getId())).thenReturn(true);
            case "missing-role" -> when(roles.findByName("STREAMER")).thenReturn(Optional.empty());
            default -> throw new AssertionError(condition);
        }
        Set<RoleEntity> originalRoles = Set.copyOf(user.getRoles());
        UserStatus originalStatus = user.getStatus();
        boolean originalEmailVerified = user.isEmailVerified();

        assertEquals(expected, assertThrows(AppException.class, () -> service.becomeStreamer(user.getId())).getErrorCode());

        assertEquals(originalRoles, user.getRoles());
        assertEquals(originalStatus, user.getStatus());
        assertEquals(originalEmailVerified, user.isEmailVerified());
        verify(users, never()).save(any());
        verify(streamers, never()).save(any());
        verify(users, never()).saveAndFlush(any());
        verify(streamers, never()).saveAndFlush(any());
        verifyNoInteractions(channel, profiles);
    }

    private static RoleEntity role(String name) {
        return RoleEntity.builder().id(UUID.randomUUID()).name(name).build();
    }
}
