package com.reallive.notificationservice.common.adapter.redis;

import com.reallive.notificationservice.common.exception.AppException;
import com.reallive.notificationservice.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.GrantedAuthority;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisAuthoritiesReaderTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisAuthoritiesReader reader = new RedisAuthoritiesReader(redis, new ObjectMapper());
    private final UUID userId = UUID.randomUUID();

    private void value(String value) {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("auth:authorities:" + userId)).thenReturn(value);
    }

    @Test
    void loadsExactNamesAndObservesChangesOnEveryRequest() {
        value("[\"ROLE_USER\",\"CHANNEL_UPDATE\"]");
        assertEquals(List.of("ROLE_USER", "CHANNEL_UPDATE"), reader.read(userId).stream()
                .map(GrantedAuthority::getAuthority).toList());
        value("[\"ROLE_USER\"]");
        assertEquals(List.of("ROLE_USER"), reader.read(userId).stream().map(GrantedAuthority::getAuthority).toList());
    }

    @Test
    void emptyArrayIsValidAndGrantsNoApplicationAuthority() {
        value("[]");
        assertTrue(reader.read(userId).isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "null", "{}", "[1]", "[null]", "[\"\"]", "[\" \"]", "[true]", "broken-json"})
    void missingOrCorruptStateFailsClosedWith503(String value) {
        value(value);
        AppException failure = assertThrows(AppException.class, () -> reader.read(userId));
        assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE, failure.getErrorCode());
        assertEquals(503, failure.getErrorCode().getHttpStatus().value());
    }

    @Test
    void redisFailureRemains503RatherThanAuthenticationFailure() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE,
                assertThrows(AppException.class, () -> reader.read(userId)).getErrorCode());
    }
}
