-- Fixed window counter. Runs atomically inside Redis (scripts are never interleaved).
-- KEYS[1] = bucket key   ARGV = limit, windowMillis, nowMillis
-- Returns {allowed (1/0), remaining, retryAfterMillis}
local limit  = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local now    = tonumber(ARGV[3])
local windowStart = now - (now % window)
local key = KEYS[1] .. ':' .. windowStart          -- one counter per window; old windows expire on their own
local count = redis.call('INCR', key)
if count == 1 then
  redis.call('PEXPIRE', key, window)               -- the counter disappears when its window is over
end
if count <= limit then
  return {1, limit - count, 0}
end
return {0, 0, windowStart + window - now}
