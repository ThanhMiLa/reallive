if redis.call('HGET', KEYS[1], 'status') == 'CLOSED' then
    return 0
end

local existingUser = redis.call('HGET', KEYS[3], ARGV[1])
if (existingUser or '') ~= ARGV[2] then
    return 2
end

local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', tonumber(ARGV[3]) - tonumber(ARGV[4]), 'LIMIT', 0, 100)
if #expired > 0 then
    redis.call('ZREM', KEYS[2], unpack(expired))
end
redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1])
if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('PEXPIRE', KEYS[2], ARGV[4])
else
    redis.call('PERSIST', KEYS[2])
end
if existingUser then
    redis.call('SREM', KEYS[4], ARGV[1])
    if redis.call('SCARD', KEYS[4]) == 0 then
        redis.call('SREM', KEYS[5], existingUser)
    end
    redis.call('HDEL', KEYS[3], ARGV[1])
end
return 1
