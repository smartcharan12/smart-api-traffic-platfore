package com.smartapi.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request (management API and gateway alike) an id, before anything else runs:
 * <ul>
 *   <li>reuse the caller's {@code X-Request-ID} if it looks safe, so one id can follow a request
 *       across several systems; otherwise generate a UUID;</li>
 *   <li>put it in the logging MDC, so every log line written while handling this request carries it;</li>
 *   <li>return it in the response header, so a client reporting a problem can quote it.</li>
 * </ul>
 * The gateway also forwards it to the backend instance.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";
    public static final String MDC_KEY = "requestId";

    // Incoming ids end up in log lines. Restricting the characters prevents log injection
    // (e.g. a header containing a newline followed by a fake log entry).
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat reuses threads. Without this, the next request on this thread would log our id.
            MDC.remove(MDC_KEY);
        }
    }

    /** The current request's id, for code running on the request thread. */
    public static String currentRequestId() {
        return MDC.get(MDC_KEY);
    }
}
