-- Token bucket with lazy refill, stored as a hash {tokens, ts}.
-- KEYS[1] = bucket key   ARGV = capacity, refillPerMillis, nowMillis
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local now      = tonumber(ARGV[3])
local state  = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])
if tokens == nil then
  tokens = capacity                                   -- new buckets start full
  ts = now
end
tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)
local allowed, retry = 0, 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry = math.ceil((1 - tokens) / rate)
end
-- Fractional tokens are stored as a string: Lua numbers returned to Redis would be truncated.
redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', now)
-- A bucket idle long enough to refill completely equals a new bucket, so it may expire.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate) + 1000)
return {allowed, math.floor(tokens), retry}
