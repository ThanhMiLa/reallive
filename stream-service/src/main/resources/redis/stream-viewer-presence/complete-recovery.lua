local state = redis.call('HGET', KEYS[1], 'status')
if not state or state == 'CLOSED' or redis.call('HGET', KEYS[1], 'generation') ~= ARGV[1] then
    return 0
end
redis.call('HSET', KEYS[1], 'status', 'ACTIVE', 'lastReconciledAt', ARGV[2])
redis.call('PERSIST', KEYS[1])
return 1
