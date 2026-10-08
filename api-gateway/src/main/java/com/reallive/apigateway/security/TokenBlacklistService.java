package com.reallive.apigateway.security;

import com.reallive.apigateway.configuration.GatewaySecurityProperties;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class TokenBlacklistService {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewaySecurityProperties properties;

    public TokenBlacklistService(ReactiveStringRedisTemplate redisTemplate, GatewaySecurityProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public Mono<Boolean> isRevoked(TokenType type, String jti) {
        return Mono.defer(() -> redisTemplate.hasKey(type.blacklistKey(jti)))
                .timeout(properties.blacklistTimeout())
                .switchIfEmpty(Mono.error(GatewaySecurityException.blacklistUnavailable()))
                .onErrorMap(exception -> GatewaySecurityException.blacklistUnavailable());
    }
}
