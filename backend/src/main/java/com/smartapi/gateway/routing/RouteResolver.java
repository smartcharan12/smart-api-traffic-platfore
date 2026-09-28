package com.smartapi.gateway.routing;

import com.smartapi.gateway.exception.InvalidGatewayRequestException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** Splits a raw gateway URI into service base path + downstream path. Pure string work, no database. */
@Component
public class RouteResolver {

    public static final String PREFIX = "/gateway";

    public Route resolve(String requestUri) {
        if (!requestUri.startsWith(PREFIX + "/") || requestUri.length() == PREFIX.length() + 1) {
            throw new InvalidGatewayRequestException("Expected /gateway/{service-path}/..., got '%s'".formatted(requestUri));
        }
        String rest = requestUri.substring(PREFIX.length());          // "/orders/orders/123"
        int secondSlash = rest.indexOf('/', 1);
        String basePath = secondSlash == -1 ? rest : rest.substring(0, secondSlash);
        String downstreamPath = secondSlash == -1 ? "/" : rest.substring(secondSlash);
        rejectDotSegments(downstreamPath);
        return new Route(basePath, downstreamPath);
    }

    /**
     * "/gateway/orders/../admin" must not become a request for "/admin" on the backend. Also catches
     * the percent-encoded form "%2e%2e" that naive string checks miss.
     */
    private static void rejectDotSegments(String path) {
        for (String segment : path.split("/")) {
            String decoded;
            try {
                decoded = URLDecoder.decode(segment, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new InvalidGatewayRequestException("Malformed percent-encoding in path");
            }
            if (decoded.equals("..") || decoded.equals(".")) {
                throw new InvalidGatewayRequestException("Relative path segments are not allowed");
            }
        }
    }
}
