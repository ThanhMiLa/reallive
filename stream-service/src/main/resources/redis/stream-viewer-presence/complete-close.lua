if redis.call('HGET', KEYS[1], 'status') ~= 'CLOSED' or redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then
    return 0
end
redis.call('DEL', KEYS[2], KEYS[3])
redis.call('HSET', KEYS[1], 'cleanupComplete', '1')
redis.call('EXPIRE', KEYS[1], ARGV[2])
redis.call('EXPIRE', KEYS[4], ARGV[2])
return 1
