local state = redis.call('HGET', KEYS[1], 'status')
if state == 'CLOSED' then
    return 0
end

if ARGV[6] ~= '' then
    if not state or redis.call('HGET', KEYS[1], 'generation') ~= ARGV[6] then
        return 0
    end
end

local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', tonumber(ARGV[4]) - tonumber(ARGV[5]), 'LIMIT', 0, 100)
if #expired > 0 then
    redis.call('ZREM', KEYS[2], unpack(expired))
end
local closedAt = redis.call('ZSCORE', KEYS[2], ARGV[3])
if closedAt and tonumber(closedAt) > tonumber(ARGV[4]) - tonumber(ARGV[5]) then
    return 0
end

local existingUser = redis.call('HGET', KEYS[3], ARGV[3])
if existingUser and existingUser ~= ARGV[2] then
    return -2
end

if not state then
    redis.call('HSET', KEYS[1], 'status', 'RECOVERING', 'generation', ARGV[1])
end
redis.call('HSET', KEYS[3], ARGV[3], ARGV[2])
redis.call('SADD', KEYS[4], ARGV[3])
redis.call('SADD', KEYS[5], ARGV[2])
for _, key in ipairs(KEYS) do
    redis.call('PERSIST', key)
end
return 1
