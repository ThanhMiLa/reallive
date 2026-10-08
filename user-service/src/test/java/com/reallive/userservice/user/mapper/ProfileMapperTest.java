package com.reallive.userservice.user.mapper;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.user.dto.response.UserProfileResponse;
import com.reallive.userservice.user.entity.ProfileEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProfileMapperTest {

    private final ProfileMapper mapper = Mappers.getMapper(ProfileMapper.class);

    @Test
    void mapsAccountIdentityAndCreationTimeAlongsideProfileDetails() {
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).email("viewer@example.com").build();
        user.setCreatedAt(LocalDateTime.of(2026, 10, 1, 8, 30));
        ProfileEntity profile = ProfileEntity.builder()
                .id(UUID.randomUUID()).user(user).displayName("Viewer")
                .avatarUrl("https://images.example.com/avatar.png").dob(LocalDate.of(2000, 2, 29)).build();
        profile.setCreatedAt(user.getCreatedAt().plusDays(1));

        UserProfileResponse response = mapper.toResponse(profile);

        assertEquals(user.getId(), response.getUserId());
        assertEquals(user.getEmail(), response.getEmail());
        assertEquals(user.getCreatedAt(), response.getCreatedAt());
        assertEquals(profile.getDisplayName(), response.getDisplayName());
        assertEquals(profile.getAvatarUrl(), response.getAvatarUrl());
        assertEquals(profile.getDob(), response.getDob());
    }

    @Test
    void preservesNullAvatarAndDateOfBirth() {
        ProfileEntity profile = ProfileEntity.builder()
                .user(UserEntity.builder().id(UUID.randomUUID()).build()).displayName("Viewer").build();

        UserProfileResponse response = mapper.toResponse(profile);

        assertNull(response.getAvatarUrl());
        assertNull(response.getDob());
    }

    @Test
    void mapsNullProfileToNull() {
        assertNull(mapper.toResponse(null));
    }
}
