package com.smartapi.gateway.security;

import com.smartapi.gateway.entity.Role;
import com.smartapi.gateway.entity.User;
import com.smartapi.gateway.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first ADMIN from ADMIN_USERNAME / ADMIN_PASSWORD (environment variables) if it doesn't
 * exist yet. There's no default password in the code, so a fresh deployment has no admin until the
 * operator provides one.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties.BootstrapAdmin admin;

    public AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder, SecurityProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.admin = properties.bootstrapAdmin();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (admin.username() == null || admin.username().isBlank() || admin.password() == null) {
            log.info("No bootstrap admin configured (ADMIN_USERNAME / ADMIN_PASSWORD)");
            return;
        }
        if (admin.password().length() < 12) {
            throw new IllegalStateException("ADMIN_PASSWORD must be at least 12 characters");
        }
        if (users.existsByUsername(admin.username())) {
            return;
        }
        users.save(new User(admin.username(), admin.email(), passwordEncoder.encode(admin.password()), Role.ADMIN));
        log.info("Created bootstrap admin '{}'", admin.username());
    }
}
