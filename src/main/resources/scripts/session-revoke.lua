-- Revokes a single session by removing its runtime state.
-- KEYS[1] = auth:session:<sid>
-- KEYS[2] = auth:refresh:<currentRefreshHash>  (empty string when unknown)
-- KEYS[3] = auth:user:<userId>:sessions
-- ARGV[1] = sid (removed from the user's SET index)
-- Return  = 1 always
if KEYS[2] ~= '' then
  redis.call('DEL', KEYS[2])
end
redis.call('DEL', KEYS[1])
redis.call('SREM', KEYS[3], ARGV[1])
return 1
