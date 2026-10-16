package com.reallive.userservice.auth.adapter.redis;

import java.time.Duration;
import java.util.UUID;

public record AuthStateSnapshot(
        UUID userId,
        long revision,
        String authorities,
        String status,
        Duration ttl
) {}
