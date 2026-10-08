package com.reallive.userservice.streamer.service.impl;

import com.reallive.userservice.auth.repository.RoleRepository;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.streamer.dto.response.BecomeStreamerResponse;
import com.reallive.userservice.streamer.repository.StreamerRepository;
import com.reallive.userservice.streamer.service.StreamerRegistrationService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.UUID;

/**
 * Service scaffold; streamer persistence and atomic registration are implemented in later steps.
 */
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamerRegistrationServiceImpl implements StreamerRegistrationService {
    UserRepository userRepository;
    RoleRepository roleRepository;
    StreamerRepository streamerRepository;

    @Override
    public BecomeStreamerResponse becomeStreamer(UUID userId) {
        throw new UnsupportedOperationException("Streamer registration is not implemented yet");
    }
}
