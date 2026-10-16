package com.reallive.paymentservice.common.adapter.redis;

import com.reallive.paymentservice.common.exception.AppException;
import com.reallive.paymentservice.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class RedisAuthoritiesReader {

    StringRedisTemplate redisTemplate;
    ObjectMapper objectMapper;

    public Collection<GrantedAuthority> read(UUID userId) {
        try {
            String value = redisTemplate.opsForValue().get("auth:authorities:" + userId);
            if (value == null) {
                throw new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE);
            }
            var node = objectMapper.readTree(value);
            if (node == null || !node.isArray()) {
                throw new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE);
            }
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            for (var authority : node) {
                if (!authority.isString() || authority.asString().isBlank()) {
                    throw new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE);
                }
                authorities.add(new SimpleGrantedAuthority(authority.asString()));
            }
            return authorities;
        } catch (RuntimeException exception) {
            throw new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE);
        }
    }
}
