package com.smartapi.gateway.controller;

import com.smartapi.gateway.exception.InvalidGatewayRequestException;
import com.smartapi.gateway.routing.GatewayRequest;
import com.smartapi.gateway.routing.GatewayResponse;
import com.smartapi.gateway.routing.GatewayService;
import com.smartapi.gateway.routing.RouteResolver;
import com.smartapi.gateway.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point for proxied traffic: every method, every path under /gateway/. Only translates between
 * the servlet API and the gateway's own request/response types; the logic is in GatewayService.
 */
@RestController
public class GatewayController {

    /** Until JWT authentication (Phase 4), the caller identifies itself with this header. It is NOT verified. */
    public static final String USER_HEADER = "X-User-Id";
    private static final Pattern VALID_USER = Pattern.compile("^[A-Za-z0-9._@-]{1,64}$");

    private final GatewayService gatewayService;
    private final ClientIpResolver clientIpResolver;

    public GatewayController(GatewayService gatewayService, ClientIpResolver clientIpResolver) {
        this.gatewayService = gatewayService;
        this.clientIpResolver = clientIpResolver;
    }

    @RequestMapping(RouteResolver.PREFIX + "/**")
    public ResponseEntity<byte[]> forward(HttpServletRequest servletRequest,
                                          @RequestBody(required = false) byte[] body) {
        GatewayRequest request = new GatewayRequest(
                servletRequest.getMethod(),
                servletRequest.getRequestURI(),
                servletRequest.getQueryString(),
                headersOf(servletRequest),
                body,
                clientIpResolver.resolve(servletRequest),
                userIdOf(servletRequest));
        GatewayResponse response = gatewayService.handle(request);
        return ResponseEntity.status(response.status()).headers(response.headers()).body(response.body());
    }

    private static HttpHeaders headersOf(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.addAll(name, Collections.list(request.getHeaders(name)));
        }
        return headers;
    }

    private static String userIdOf(HttpServletRequest request) {
        String userId = request.getHeader(USER_HEADER);
        if (userId == null) {
            return null;
        }
        if (!VALID_USER.matcher(userId).matches()) {
            throw new InvalidGatewayRequestException(USER_HEADER + " contains invalid characters");
        }
        return userId;
    }
}
