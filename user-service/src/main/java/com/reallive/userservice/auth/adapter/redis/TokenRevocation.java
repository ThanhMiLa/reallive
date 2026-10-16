package com.reallive.userservice.auth.adapter.redis;

import java.time.Duration;

public record TokenRevocation(String key, Duration ttl) {
}
