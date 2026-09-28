package com.smartapi.gateway.resilience;

import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;

/**
 * Decides whether a failed attempt may be retried. Two questions:
 *
 * <p>1. Is the failure TRANSIENT (might succeed next time)?
 * <ul>
 *   <li>connection refused / connect timeout: yes (the instance may be restarting, or we try another);</li>
 *   <li>response timeout, 502, 503, 504: yes, typically overload or a restart;</li>
 *   <li>500: no. Usually a bug that will fail the same way again (a PERMANENT failure);</li>
 *   <li>4xx: no. The request itself is wrong; sending it again cannot help.</li>
 * </ul>
 *
 * <p>2. Is it SAFE to send the request again?
 * <ul>
 *   <li>If the connection was never established, the backend never saw the request: always safe.</li>
 *   <li>Otherwise the backend may have processed it. Only idempotent methods (GET, HEAD, OPTIONS, PUT,
 *       DELETE: doing them twice has the same effect as once) or requests carrying an
 *       {@code Idempotency-Key} header (the client promises the backend de-duplicates) are retried.
 *       A POST that charges a card is never replayed blindly.</li>
 * </ul>
 */
public final class RetryDecider {

    public enum FailureKind {
        CONNECT_FAILED,
        TIMEOUT,
        BAD_GATEWAY,
        RETRYABLE_STATUS,
        NON_RETRYABLE_STATUS
    }

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(502, 503, 504);

    public static FailureKind classifyStatus(int status) {
        return RETRYABLE_STATUSES.contains(status) ? FailureKind.RETRYABLE_STATUS : FailureKind.NON_RETRYABLE_STATUS;
    }

    public boolean isRetryable(FailureKind kind, String method, HttpHeaders headers) {
        return switch (kind) {
            case CONNECT_FAILED -> true;
            case TIMEOUT, BAD_GATEWAY, RETRYABLE_STATUS -> isIdempotent(method, headers);
            case NON_RETRYABLE_STATUS -> false;
        };
    }

    public static boolean isIdempotent(String method, HttpHeaders headers) {
        return IDEMPOTENT_METHODS.contains(method.toUpperCase(Locale.ROOT)) || headers.containsKey(IDEMPOTENCY_KEY);
    }
}
