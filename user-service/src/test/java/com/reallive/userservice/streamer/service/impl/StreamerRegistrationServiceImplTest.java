package com.reallive.userservice.streamer.service.impl;

import com.reallive.userservice.auth.entity.RoleEntity;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.entity.UserStatus;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.streamer.entity.StreamerEntity;
import com.reallive.userservice.streamer.entity.StreamerStatus;
import com.reallive.userservice.streamer.repository.StreamerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamerRegistrationServiceImplTest {

    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final StreamerRepository streamers = mock(StreamerRepository.class);
    private final StreamerRegistrationServiceImpl service = new StreamerRegistrationServiceImpl(users, roles, streamers);
    private final RoleEntity streamerRole = role("STREAMER");
    private UserEntity user;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).emailVerified(true)
                .roles(new HashSet<>(Set.of(role("USER"), role("MODERATOR")))).build();
        when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(roles.findByName("STREAMER")).thenReturn(Optional.of(streamerRole));
    }

    @Test
    void locksAndValidatesBeforeAddingRoleAndPersistingActiveStreamer() {
        UUID streamerId = UUID.randomUUID();
        when(streamers.save(any())).thenAnswer(invocation -> {
            StreamerEntity streamer = invocation.getArgument(0);
            streamer.setId(streamerId);
            return streamer;
        });

        var response = service.becomeStreamer(user.getId());

        var captured = ArgumentCaptor.forClass(StreamerEntity.class);
        var order = inOrder(users, roles, streamers);
        order.verify(users).findByIdForUpdate(user.getId());
        order.verify(streamers).existsByUserId(user.getId());
        order.verify(roles).findByName("STREAMER");
        order.verify(users).save(user);
        order.verify(streamers).save(captured.capture());
        assertSame(user, captured.getValue().getUser());
        assertEquals(StreamerStatus.ACTIVE, captured.getValue().getStatus());
        assertNotNull(captured.getValue().getVerifiedAt());
        assertEquals(streamerId, response.getStreamerId());
        assertEquals(user.getId(), response.getUserId());
        assertEquals(StreamerStatus.ACTIVE, response.getStatus());
        assertEquals(Set.of("USER", "MODERATOR", "STREAMER"), response.getRoles());
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
    }

    private static RoleEntity role(String name) {
        return RoleEntity.builder().id(UUID.randomUUID()).name(name).build();
    }
}
