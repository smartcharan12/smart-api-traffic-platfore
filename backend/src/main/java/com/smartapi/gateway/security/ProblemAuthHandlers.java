package com.smartapi.gateway.security;

import com.smartapi.gateway.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** 401 / 403 in the same problem+json shape as every other API error. */
final class ProblemAuthHandlers {

    private ProblemAuthHandlers() {
    }

    static AuthenticationEntryPoint unauthorized() {
        return (request, response, e) -> write(response, 401, "Unauthorized",
                e instanceof AuthenticationException && e.getMessage() != null && e.getMessage().contains("expired")
                        ? "Access token expired" : "A valid Bearer access token is required", true);
    }

    static AccessDeniedHandler forbidden() {
        return (request, response, e) -> write(response, 403, "Forbidden",
                "Your role does not allow this operation", false);
    }

    private static void write(HttpServletResponse response, int status, String title, String detail,
                              boolean challenge) throws IOException {
        if (challenge) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.setStatus(status);
        response.setContentType("application/problem+json");
        String requestId = RequestIdFilter.currentRequestId();
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\"%s}"
                .formatted(title, status, detail, requestId == null ? "" : ",\"requestId\":\"" + requestId + "\""));
    }
}
