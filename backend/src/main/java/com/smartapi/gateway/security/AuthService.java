package com.smartapi.gateway.security;

import com.smartapi.gateway.entity.Role;
import com.smartapi.gateway.entity.User;
import com.smartapi.gateway.exception.DuplicateResourceException;
import com.smartapi.gateway.ratelimit.FixedWindowRateLimiter;
import com.smartapi.gateway.ratelimit.RateLimitDecision;
import com.smartapi.gateway.exception.RateLimitExceededException;
import com.smartapi.gateway.repository.UserRepository;
import java.time.Clock;
import java.util.Optional;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final FixedWindowRateLimiter loginLimiter;
    /**
     * A real hash to compare against when the username doesn't exist, so "unknown user" and "wrong
     * password" take about the same time. Otherwise response timing reveals which usernames exist.
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                       SecurityProperties properties, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        // Reuses the Phase 2 fixed-window limiter: N login attempts per window per client IP.
        this.loginLimiter = new FixedWindowRateLimiter(properties.loginRateLimit().attempts(),
                properties.loginRateLimit().window(), clock);
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /** Self-registration always creates a USER; admins are created by the bootstrap or promoted in the database. */
    @Transactional
    public User register(String username, String email, String password) {
        if (users.existsByUsername(username)) {
            throw new DuplicateResourceException("Username '%s' is taken".formatted(username));
        }
        if (users.existsByEmail(email)) {
            throw new DuplicateResourceException("Email is already registered");
        }
        return users.save(new User(username, email, passwordEncoder.encode(password), Role.USER));
    }

    /** Same error for unknown user and wrong password, so the API doesn't confirm which usernames exist. */
    @Transactional(readOnly = true)
    public JwtService.Token login(String username, String password, String clientIp) {
        RateLimitDecision decision = loginLimiter.tryAcquire("login:" + clientIp);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision);
        }
        Optional<User> user = users.findByUsername(username);
        String hash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(password, hash);
        if (user.isEmpty() || !matches) {
            throw new BadCredentialsException("Invalid username or password");
        }
        return jwtService.issue(user.get());
    }

    @Transactional(readOnly = true)
    public Optional<User> find(String username) {
        return users.findByUsername(username);
    }
}
