if redis.call('HGET', KEYS[1], 'status') == 'CLOSED' and redis.call('HGET', KEYS[1], 'cleanupComplete') == '1' then
    return 0
end
redis.call('HSET', KEYS[1], 'status', 'CLOSED', 'generation', ARGV[1], 'closedAt', ARGV[2], 'cleanupComplete', '0')
for _, key in ipairs(KEYS) do
    redis.call('PERSIST', key)
end
return 1
