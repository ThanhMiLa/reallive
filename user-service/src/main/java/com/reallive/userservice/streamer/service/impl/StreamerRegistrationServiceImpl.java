package com.reallive.userservice.streamer.service.impl;

import com.reallive.userservice.auth.entity.RoleEntity;
import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.util.UserValidator;
import com.reallive.userservice.streamer.dto.response.BecomeStreamerResponse;
import com.reallive.userservice.streamer.entity.StreamerEntity;
import com.reallive.userservice.streamer.entity.StreamerStatus;
import com.reallive.userservice.streamer.repository.StreamerRepository;
import com.reallive.userservice.streamer.service.StreamerRegistrationService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamerRegistrationServiceImpl implements StreamerRegistrationService {
    UserRepository userRepository;
    RoleRepository roleRepository;
    StreamerRepository streamerRepository;

    @Override
    @Transactional
    public BecomeStreamerResponse becomeStreamer(UUID userId) {
        UserEntity user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        validateEligibility(user);

        RoleEntity streamerRole = roleRepository.findByName("STREAMER")
                .orElseThrow(() -> new AppException(ErrorCode.STREAMER_ROLE_NOT_FOUND));

        user.getRoles().add(streamerRole);
        userRepository.save(user);

        StreamerEntity streamerEntity = StreamerEntity.builder()
                .user(user)
                .status(StreamerStatus.ACTIVE)
                .verifiedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();

        StreamerEntity streamer = streamerRepository.save(streamerEntity);

        return BecomeStreamerResponse.builder()
                .streamerId(streamer.getId())
                .userId(user.getId())
                .status(streamer.getStatus())
                .roles(user.getRoles()
                        .stream()
                        .map(RoleEntity::getName)
                        .collect(Collectors.toSet()))
                .build();
    }

    private void validateEligibility(UserEntity user) {
        UserValidator.validateActive(user);
        UserValidator.validateEmailVerified(user);
        if (user.getRoles().stream().anyMatch(role -> "STREAMER".equals(role.getName()))
                || streamerRepository.existsByUserId(user.getId())) {
            throw new AppException(ErrorCode.ALREADY_STREAMER);
        }
    }
}
