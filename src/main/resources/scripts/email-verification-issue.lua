-- KEYS[1] = token key (token-key-prefix + tokenHash)
-- KEYS[2] = user pointer key (user-key-prefix + userId)
-- ARGV[1] = JSON payload to store at the token key
-- ARGV[2] = token hash (value to store at the user pointer key)
-- ARGV[3] = ttl in seconds (same TTL for both keys)
redis.call('SETEX', KEYS[1], ARGV[3], ARGV[1])
redis.call('SETEX', KEYS[2], ARGV[3], ARGV[2])
return 1
