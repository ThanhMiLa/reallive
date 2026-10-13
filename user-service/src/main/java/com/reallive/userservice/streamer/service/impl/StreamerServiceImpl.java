package com.reallive.userservice.streamer.service.impl;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
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

    @Override
    @Transactional(readOnly = true)
    public UUID getActiveStreamerIdByUserId(UUID userId) {
        StreamerEntity streamer = streamerRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.STREAMER_NOT_FOUND));
        if (streamer.getStatus() != StreamerStatus.ACTIVE) {
            throw new AppException(ErrorCode.STREAMER_NOT_ACTIVE);
        }
        return streamer.getId();
    }
}
