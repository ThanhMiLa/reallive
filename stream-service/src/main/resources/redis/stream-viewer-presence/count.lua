local state = redis.call('HGET', KEYS[1], 'status')
if state == 'CLOSED' then
    return 0
end
if state ~= 'ACTIVE' then
    return -1
end
return redis.call('SCARD', KEYS[2])
