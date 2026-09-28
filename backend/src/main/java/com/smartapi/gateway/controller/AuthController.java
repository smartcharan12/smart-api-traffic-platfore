package com.smartapi.gateway.controller;

import com.smartapi.gateway.dto.AuthDtos;
import com.smartapi.gateway.entity.User;
import com.smartapi.gateway.exception.ResourceNotFoundException;
import com.smartapi.gateway.security.AuthService;
import com.smartapi.gateway.security.JwtService;
import com.smartapi.gateway.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;

    public AuthController(AuthService authService, ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthDtos.UserResponse register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return toResponse(authService.register(request.username(), request.email(), request.password()));
    }

    @PostMapping("/login")
    public AuthDtos.TokenResponse login(@Valid @RequestBody AuthDtos.LoginRequest request, HttpServletRequest http) {
        JwtService.Token token = authService.login(request.username(), request.password(), clientIpResolver.resolve(http));
        User user = authService.find(request.username()).orElseThrow();
        return new AuthDtos.TokenResponse(token.value(), "Bearer", token.expiresAt(), user.getUsername(), user.getRole());
    }

    /** Who am I? The username comes from the verified token, never from the request body. */
    @GetMapping("/me")
    public AuthDtos.UserResponse me(Authentication authentication) {
        return authService.find(authentication.getName()).map(AuthController::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("User", authentication.getName()));
    }

    private static AuthDtos.UserResponse toResponse(User user) {
        return new AuthDtos.UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole(),
                user.getCreatedAt());
    }
}
