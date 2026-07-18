-- KEYS[1] = base key (prefix + caller key, without window suffix)
-- ARGV[1] = limit (max requests per window)
-- ARGV[2] = window size in seconds
local base = KEYS[1]
local limit = tonumber(ARGV[1])
local windowSize = tonumber(ARGV[2])

local time = redis.call('TIME')
local nowSeconds = tonumber(time[1])

local currentIdx = math.floor(nowSeconds / windowSize)
local prevIdx = currentIdx - 1
local elapsedFraction = (nowSeconds - (currentIdx * windowSize)) / windowSize

local currentKey = base .. ':' .. currentIdx
local prevKey = base .. ':' .. prevIdx

local currentCount = tonumber(redis.call('GET', currentKey) or '0')
local prevCount = tonumber(redis.call('GET', prevKey) or '0')

local weighted = (prevCount * (1 - elapsedFraction)) + currentCount

if weighted + 1 > limit then
  return 0
end

local newVal = redis.call('INCR', currentKey)
if newVal == 1 then
  redis.call('EXPIRE', currentKey, windowSize * 2)
end

return 1
