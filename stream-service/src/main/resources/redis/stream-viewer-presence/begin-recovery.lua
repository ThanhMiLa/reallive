if redis.call('HGET', KEYS[1], 'status') == 'CLOSED' then
    return 0
end
redis.call('HSET', KEYS[1], 'status', 'RECOVERING', 'generation', ARGV[1])
redis.call('PERSIST', KEYS[1])
local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', tonumber(ARGV[2]) - tonumber(ARGV[3]), 'LIMIT', 0, 100)
if #expired > 0 then
    redis.call('ZREM', KEYS[2], unpack(expired))
end
return 1
