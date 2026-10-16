package com.reallive.userservice.auth.adapter.redis;

import com.reallive.userservice.common.exception.AppException;
import com.reallive.userservice.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class RedisAuthStateStore implements AuthStateStore {

    static DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            for i = 1, #KEYS do
                local kind = redis.call('TYPE', KEYS[i]).ok
                if kind ~= 'none' and kind ~= 'string' then return -3 end
            end
            for i = 4, #KEYS do
                if redis.call('EXISTS', KEYS[i]) == 1 then return -1 end
            end
            local revision = tonumber(redis.call('GET', KEYS[3]) or '-1')
            if not revision then return -3 end
            local incoming = tonumber(ARGV[1])
            if ARGV[5] == '1' and (ARGV[3] == 'BANNED' or
                (revision > incoming and redis.call('GET', KEYS[2]) == 'BANNED')) then return -2 end
            if revision > incoming and (redis.call('EXISTS', KEYS[1]) == 0 or
                redis.call('EXISTS', KEYS[2]) == 0) then return -3 end
            local ttl = tonumber(ARGV[4])
            for i = 1, 3 do ttl = math.max(ttl, redis.call('PTTL', KEYS[i])) end
            if revision <= incoming then
                redis.call('SET', KEYS[1], ARGV[2], 'PX', ttl)
                redis.call('SET', KEYS[2], ARGV[3], 'PX', ttl)
                redis.call('SET', KEYS[3], ARGV[1], 'PX', ttl)
            else
                for i = 1, 3 do redis.call('PEXPIRE', KEYS[i], ttl) end
            end
            for i = 4, #KEYS do
                redis.call('SET', KEYS[i], '1', 'PX', ARGV[i + 2])
            end
            return 1
            """, Long.class);

    static DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>("""
            for i = 1, #KEYS do
                if redis.call('EXISTS', KEYS[i]) == 1 then return -1 end
            end
            for i = 1, #KEYS do redis.call('SET', KEYS[i], '1', 'PX', ARGV[i]) end
            return 1
            """, Long.class);

    StringRedisTemplate redisTemplate;

    @Override
    public void publish(AuthStateSnapshot snapshot, List<TokenRevocation> revocations, boolean issuingTokens) {
        List<String> keys = new ArrayList<>(List.of("auth:authorities:" + snapshot.userId(),
                "auth:status:" + snapshot.userId(), "auth:revision:" + snapshot.userId()));
        List<String> arguments = new ArrayList<>(List.of(Long.toString(snapshot.revision()), snapshot.authorities(),
                snapshot.status(), Long.toString(snapshot.ttl().toMillis()), issuingTokens ? "1" : "0"));
        for (TokenRevocation revocation : revocations) {
            keys.add(revocation.key());
            arguments.add(Long.toString(Math.max(1, revocation.ttl().toMillis())));
        }
        checkResult(execute(PUBLISH, keys, arguments));
    }

    @Override
    public void revoke(List<TokenRevocation> revocations) {
        if (revocations.isEmpty()) {
            return;
        }
        List<String> keys = revocations.stream().map(TokenRevocation::key).toList();
        List<String> ttl = revocations.stream().map(value -> Long.toString(Math.max(1, value.ttl().toMillis()))).toList();
        checkResult(execute(REVOKE, keys, ttl));
    }

    private Long execute(DefaultRedisScript<Long> script, List<String> keys, List<String> arguments) {
        try {
            return redisTemplate.execute(script, keys, arguments.toArray());
        } catch (RuntimeException exception) {
            throw new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE);
        }
    }

    private void checkResult(Long result) {
        if (Long.valueOf(-1).equals(result)) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (Long.valueOf(-2).equals(result)) {
            throw new AppException(ErrorCode.USER_NOT_ACTIVE);
        }
        if (!Long.valueOf(1).equals(result)) {
            throw new AppException(ErrorCode.AUTH_STATE_UNAVAILABLE);
        }
    }
}
