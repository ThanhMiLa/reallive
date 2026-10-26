package com.reallive.userservice.streamer.service.impl;

import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import com.reallive.userservice.common.util.UserValidator;
import com.reallive.userservice.streamer.dto.response.StreamingEligibilityResponse;
import com.reallive.userservice.streamer.entity.StreamerEntity;
import com.reallive.userservice.streamer.entity.StreamerStatus;
import com.reallive.userservice.streamer.repository.StreamerRepository;
import com.reallive.userservice.streamer.service.StreamerService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamerServiceImpl implements StreamerService {

    StreamerRepository streamerRepository;
    UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UUID getActiveStreamerIdByUserId(UUID userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        return requireActiveStreamer(userId).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public StreamingEligibilityResponse getStreamingEligibility(UUID userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        UserValidator.validateEmailVerified(user);
        return new StreamingEligibilityResponse(userId, requireActiveStreamer(userId).getId());
    }

    private StreamerEntity requireActiveStreamer(UUID userId) {
        StreamerEntity streamer = streamerRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.STREAMER_NOT_FOUND));
        if (streamer.getStatus() != StreamerStatus.ACTIVE) {
            throw new AppException(ErrorCode.STREAMER_NOT_ACTIVE);
        }

        return streamer;
    }
}
