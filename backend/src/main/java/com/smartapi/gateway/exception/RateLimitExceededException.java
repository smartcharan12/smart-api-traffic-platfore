package com.smartapi.gateway.exception;

import com.smartapi.gateway.ratelimit.RateLimitDecision;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** 429: the client used up its budget. Retry-After tells it when to try again. */
public class RateLimitExceededException extends GatewayException {

    private final RateLimitDecision decision;

    public RateLimitExceededException(RateLimitDecision decision) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                "Rate limit of %d requests exceeded; retry after %d s"
                        .formatted(decision.limit(), decision.retryAfterSeconds()));
        this.decision = decision;
    }

    @Override
    public HttpHeaders responseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        headers.set("X-RateLimit-Limit", String.valueOf(decision.limit()));
        headers.set("X-RateLimit-Remaining", "0");
        return headers;
    }
}
