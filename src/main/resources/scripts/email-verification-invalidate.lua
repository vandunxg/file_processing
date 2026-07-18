-- KEYS[1] = user pointer key (user-key-prefix + userId)
-- ARGV[1] = token key prefix (to reconstruct the old token's key)
local oldHash = redis.call('GET', KEYS[1])
if oldHash then
  redis.call('DEL', ARGV[1] .. oldHash)
  redis.call('DEL', KEYS[1])
end
return 1
