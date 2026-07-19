-- KEYS[1] = auth:refresh:<oldHash>
-- KEYS[2] = auth:refresh:used:<oldHash>
-- KEYS[3] = auth:refresh:<newHash>
-- KEYS[4] = auth:session:<sid>
-- ARGV[1] = expected sid (string)
-- ARGV[2] = newHash
-- ARGV[3] = remaining session TTL, ms (integer)
-- ARGV[4] = reuse-detection grace, ms (integer)
-- ARGV[5] = new lastUsedAt ISO-8601 string
-- Return  = 1 on success, 0 when the caller's old hash no longer maps to the expected sid
local stored = redis.call('GET', KEYS[1])
if not stored or stored ~= ARGV[1] then
  return 0
end
redis.call('DEL', KEYS[1])
redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[4])
redis.call('SET', KEYS[3], ARGV[1], 'PX', ARGV[3])
redis.call('HSET', KEYS[4], 'refreshTokenHash', ARGV[2], 'lastUsedAt', ARGV[5])
return 1
