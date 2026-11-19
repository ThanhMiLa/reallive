local state = redis.call('HGET', KEYS[1], 'status')
if state == 'CLOSED' then
    return {'CLOSED'}
end
if state ~= 'ACTIVE' then
    return {'UNAVAILABLE'}
end
local scan = redis.call('SSCAN', KEYS[2], ARGV[1], 'COUNT', ARGV[2])
local result = {'ACTIVE', scan[1]}
local members = scan[2]
for i = 1, #members do
    result[#result + 1] = members[i]
end
return result
