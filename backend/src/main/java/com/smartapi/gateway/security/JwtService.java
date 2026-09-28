package com.smartapi.gateway.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartapi.gateway.entity.User;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Issues signed access tokens: {@code {"iss", "sub": username, "role": "ADMIN"|"USER", "iat", "exp"}},
 * HMAC-SHA256 with a server-side secret. Anyone can read a JWT's claims (they're base64, not
 * encrypted); nobody can change them without invalidating the signature. So a token never contains
 * secrets, and the server trusts its claims only after verifying the signature, issuer and expiry
 * (SecurityConfig's JwtDecoder).
 */
@Service
public class JwtService {

    public record Token(String value, Instant expiresAt) {
    }

    private final NimbusJwtEncoder encoder;
    private final SecurityProperties.Jwt config;
    private final Clock clock;

    public JwtService(SecurityProperties properties, Clock clock) {
        this.config = properties.jwt();
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(config.secret())));
        this.clock = clock;
    }

    static SecretKey secretKey(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public Token issue(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(config.ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(config.issuer())
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("role", user.getRole().name())
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new Token(value, expiresAt);
    }
}
