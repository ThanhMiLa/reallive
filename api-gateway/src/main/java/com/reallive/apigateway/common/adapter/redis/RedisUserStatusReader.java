package com.reallive.apigateway.common.adapter.redis;

import com.reallive.apigateway.configuration.GatewaySecurityProperties;
import com.reallive.apigateway.security.GatewaySecurityException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RedisUserStatusReader {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewaySecurityProperties properties;

    public RedisUserStatusReader(ReactiveStringRedisTemplate redisTemplate, GatewaySecurityProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public Mono<String> read(String userId) {
        return Mono.defer(() -> redisTemplate.opsForValue().get("auth:status:" + userId))
                .timeout(properties.blacklistTimeout())
                .switchIfEmpty(Mono.error(GatewaySecurityException.blacklistUnavailable()))
                .onErrorMap(exception -> GatewaySecurityException.blacklistUnavailable());
    }
}
