package com.smartapi.gateway.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code security.*} settings. Secrets come from environment variables (see .env.example); the
 * application refuses to start without a JWT secret of at least 32 bytes.
 *
 * @param requireAuthenticationForGateway if false, anonymous clients may use /gateway/** (rate-limited
 *                                        per IP); a present-but-invalid token is always rejected
 * @param maxRequestBytes                 larger request bodies are rejected with 413
 * @param trustUserIdHeader               use the unverified X-User-Id header as identity when no JWT is
 *                                        present. Only for tests/local demos: anyone can send any value.
 */
@Validated
@ConfigurationProperties(prefix = "security")
public record SecurityProperties(
        @Valid Jwt jwt,
        @DefaultValue("http://localhost:5173") List<String> corsAllowedOrigins,
        @Valid @DefaultValue BootstrapAdmin bootstrapAdmin,
        @DefaultValue("false") boolean requireAuthenticationForGateway,
        @DefaultValue("1048576") @Min(1) long maxRequestBytes,
        @Valid @DefaultValue LoginRateLimit loginRateLimit,
        @DefaultValue("false") boolean trustUserIdHeader) {

    /** HS256 needs a key of at least 256 bits = 32 bytes. */
    public record Jwt(
            @NotBlank @Size(min = 32, message = "must be at least 32 characters (HS256 needs a 256-bit key)") String secret,
            @DefaultValue("1h") Duration ttl,
            @DefaultValue("smart-api-gateway") String issuer) {
    }

    /** Created on startup if both are set and no user with that name exists. Never committed. */
    public record BootstrapAdmin(String username, String password, @DefaultValue("admin@localhost") String email) {
    }

    /** Brute-force protection for POST /api/auth/login, per client IP. */
    public record LoginRateLimit(@DefaultValue("10") @Min(1) int attempts, @DefaultValue("1m") Duration window) {
    }
}
