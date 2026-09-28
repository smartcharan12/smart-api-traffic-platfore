package com.smartapi.gateway.ratelimit;

/** Where rate-limit state lives. */
public enum RateLimitStore {
    /** In this gateway's memory: fast, but each gateway instance has its own budget. */
    LOCAL,
    /** In Redis, shared by all gateway instances; falls back to LOCAL while Redis is unavailable. */
    REDIS
}
