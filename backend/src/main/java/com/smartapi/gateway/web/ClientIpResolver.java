package com.smartapi.gateway.web;

import com.smartapi.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Determines the client's IP address for rate limiting and logs.
 *
 * <p>By default we use the TCP peer address ({@code getRemoteAddr()}), which the client cannot fake.
 * {@code X-Forwarded-For} is just a header: any client can send
 * {@code X-Forwarded-For: 1.2.3.4} and, if we trusted it, get a fresh rate-limit budget on every
 * request by changing the value. It is only trustworthy when a proxy we control sits in front of the
 * gateway and appends the real peer address. In that case the <em>rightmost</em> entry is the one our
 * proxy added; everything to its left came from the client and may be forged.
 */
@Component
public class ClientIpResolver {

    private final boolean trustForwardedFor;

    public ClientIpResolver(GatewayProperties properties) {
        this.trustForwardedFor = properties.rateLimit().trustForwardedFor();
    }

    public String resolve(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                String[] hops = forwardedFor.split(",");
                String last = hops[hops.length - 1].trim();
                if (!last.isEmpty()) {
                    return last;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
