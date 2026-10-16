package com.reallive.apigateway.security;

import com.reallive.apigateway.common.adapter.redis.RedisUserStatusReader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Set;

@Service
public class UserStatusService {

    private final RedisUserStatusReader statusReader;

    public UserStatusService(RedisUserStatusReader statusReader) {
        this.statusReader = statusReader;
    }

    public Mono<Void> requireActive(String userId) {
        return statusReader.read(userId)
                .flatMap(status -> switch (status) {
                    case "ACTIVE" -> Mono.empty();
                    case "BANNED" -> Mono.error(new GatewaySecurityException(HttpStatus.FORBIDDEN, 1003,
                            "User is not active", Set.of()));
                    default -> Mono.error(GatewaySecurityException.blacklistUnavailable());
                });
    }
}
