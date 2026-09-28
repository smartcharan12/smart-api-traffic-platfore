package com.smartapi.gateway.resilience;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Limits retries to a fraction of normal traffic, so retries can never multiply load during an outage
 * (a "retry storm"). Every first attempt deposits {@code ratio} tokens (e.g. 0.2); every retry withdraws
 * one. Over time at most ~20% extra requests can be retries, however many requests fail.
 * {@code maxTokens} caps how much budget a quiet period can save up.
 *
 * <p>Lock-free: tokens are stored as thousandths in an {@link AtomicLong} and updated with a
 * compare-and-set loop. If another thread changed the value between our read and our write, the CAS
 * fails and we simply re-read and try again. No thread ever blocks.
 */
public final class RetryBudget {

    private static final long SCALE = 1000;

    private final long depositPerRequest;
    private final long maxTokens;
    private final AtomicLong tokens;

    public RetryBudget(double ratio, int maxTokens, int initialTokens) {
        if (ratio <= 0 || maxTokens < 1 || initialTokens < 0 || initialTokens > maxTokens) {
            throw new IllegalArgumentException("invalid retry budget");
        }
        this.depositPerRequest = Math.round(ratio * SCALE);
        this.maxTokens = maxTokens * SCALE;
        this.tokens = new AtomicLong(initialTokens * SCALE);
    }

    /** Called once per client request (not per retry). */
    public void recordRequest() {
        long current;
        long next;
        do {
            current = tokens.get();
            next = Math.min(maxTokens, current + depositPerRequest);
        } while (current != next && !tokens.compareAndSet(current, next));
    }

    /** @return true if a retry may be attempted (and consumes one token) */
    public boolean tryWithdraw() {
        long current;
        do {
            current = tokens.get();
            if (current < SCALE) {
                return false;
            }
        } while (!tokens.compareAndSet(current, current - SCALE));
        return true;
    }

    public double available() {
        return tokens.get() / (double) SCALE;
    }
}
