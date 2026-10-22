package com.reallive.streamservice.common.adapter.redis;

import com.reallive.streamservice.common.exception.AppException;
import com.reallive.streamservice.common.exception.ErrorCode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisAuthoritiesReaderTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisAuthoritiesReader reader = new RedisAuthoritiesReader(redisTemplate, new ObjectMapper());
    private final UUID userId = UUID.randomUUID();

    @Test
    void observesPermissionChangesWithoutCachingAuthorities() {
        storedValue("[\"ROLE_STREAMER\",\"STREAM_CREATE\"]");
        assertEquals(List.of("ROLE_STREAMER", "STREAM_CREATE"), reader.read(userId).stream()
                .map(GrantedAuthority::getAuthority).toList());

        storedValue("[\"ROLE_USER\"]");
        assertEquals(List.of("ROLE_USER"), reader.read(userId).stream()
                .map(GrantedAuthority::getAuthority).toList());
    }

    @Test
    void emptyArrayGrantsNoApplicationAuthorities() {
        storedValue("[]");
        assertTrue(reader.read(userId).isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "null", "{}", "[1]", "[null]", "[\"\"]", "[\" \"]", "[true]", "broken-json"})
    void missingOrCorruptStateRejectsAuthentication(String value) {
        storedValue(value);
        AppException exception = assertThrows(AppException.class, () -> reader.read(userId));
        assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void connectionFailuresUseTheUnavailableAuthStateError() {
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));

        AppException exception = assertThrows(AppException.class, () -> reader.read(userId));
        assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE, exception.getErrorCode());
    }

    private void storedValue(String value) {
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get("auth:authorities:" + userId)).thenReturn(value);
    }
}
