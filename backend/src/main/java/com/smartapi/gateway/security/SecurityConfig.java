package com.smartapi.gateway.security;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Two filter chains:
 * <ol>
 *   <li><b>/gateway/**</b> (proxied traffic): a Bearer token is optional by default (anonymous clients
 *       are rate-limited per IP) but, if present, must be valid. With
 *       {@code security.require-authentication-for-gateway=true} a token is mandatory. Cache-Control
 *       headers are NOT added here: the backend owns its responses' caching semantics.</li>
 *   <li><b>everything else</b> (management API):
 *       <pre>
 *       public:        GET /api/health, POST /api/auth/login|register, /actuator/health, /actuator/prometheus
 *       any user:      GET /api/**                       (dashboard reads)
 *       ADMIN only:    every other /api/** method        (register/delete services, change status,
 *                                                          run health checks, inject failures)
 *       </pre></li>
 * </ol>
 * Both are STATELESS (no HTTP session): each request carries its JWT. CSRF protection is off because
 * credentials travel in the Authorization header, which browsers never attach automatically, unlike
 * cookies. /actuator/prometheus is public for the scraper and must be network-restricted in production
 * (it is not published by docker-compose).
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /** BCrypt: salted, deliberately slow (cost 10 ≈ tens of ms), so stolen hashes are expensive to brute-force. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Verifies signature (HS256), expiry and issuer; anything else is rejected with 401. Expiry is
     * checked against the same injected Clock that JwtService uses to issue tokens: two components
     * with different notions of "now" would disagree about whether a token is valid.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecurityProperties properties, java.time.Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(JwtService.secretKey(properties.jwt().secret()))
                .macAlgorithm(MacAlgorithm.HS256).build();
        org.springframework.security.oauth2.jwt.JwtTimestampValidator expiry =
                new org.springframework.security.oauth2.jwt.JwtTimestampValidator();
        expiry.setClock(clock);
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                expiry, new org.springframework.security.oauth2.jwt.JwtIssuerValidator(properties.jwt().issuer())));
        return decoder;
    }

    /** The token's "role" claim becomes the Spring authority ROLE_ADMIN / ROLE_USER. */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain gatewayChain(HttpSecurity http, SecurityProperties properties,
                                            JwtAuthenticationConverter converter) throws Exception {
        http.securityMatcher("/gateway/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(a -> {
                    if (properties.requireAuthenticationForGateway()) {
                        a.anyRequest().authenticated();
                    } else {
                        a.anyRequest().permitAll();
                    }
                })
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(ProblemAuthHandlers.unauthorized()))
                .exceptionHandling(e -> e.authenticationEntryPoint(ProblemAuthHandlers.unauthorized())
                        .accessDeniedHandler(ProblemAuthHandlers.forbidden()))
                .headers(h -> h.cacheControl(c -> c.disable()));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiChain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(ProblemAuthHandlers.unauthorized()))
                .exceptionHandling(e -> e.authenticationEntryPoint(ProblemAuthHandlers.unauthorized())
                        .accessDeniedHandler(ProblemAuthHandlers.forbidden()))
                // Defaults already add X-Content-Type-Options: nosniff, X-Frame-Options: DENY,
                // Cache-Control: no-store and (over HTTPS) HSTS. The API returns JSON only, so the
                // strictest CSP applies.
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    /**
     * CORS lets the browser-based dashboard, served from another origin, call the API. Only the
     * configured origins are allowed; response headers the dashboard reads are exposed explicitly.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-ID", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("X-Request-ID", "X-RateLimit-Limit", "X-RateLimit-Remaining", "Retry-After",
                "X-Cache", "X-Retry-Count", "Location"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
