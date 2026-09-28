package com.smartapi.gateway.dto;

import com.smartapi.gateway.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Request/response bodies of /api/auth/**. */
public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * Password 12..72 characters: BCrypt only uses the first 72 bytes, so longer passwords would be
     * silently truncated (two passwords differing after byte 72 would both work).
     */
    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                    message = "letters, digits, '.', '_' and '-' only") String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 12, max = 72) String password) {
    }

    public record LoginRequest(@NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 72) String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt, String username, Role role) {
    }

    public record UserResponse(long id, String username, String email, Role role, Instant createdAt) {
    }
}
