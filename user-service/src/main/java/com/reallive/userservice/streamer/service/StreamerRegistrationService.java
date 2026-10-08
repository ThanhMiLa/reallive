package com.reallive.userservice.streamer.service;

import com.reallive.userservice.streamer.dto.response.BecomeStreamerResponse;

import java.util.UUID;

public interface StreamerRegistrationService {
    BecomeStreamerResponse becomeStreamer(UUID userId);
}
