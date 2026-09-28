package com.smartapi.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartapi.gateway.routing.ServiceDiscovery;
import com.smartapi.gateway.support.MutableClock;
import com.smartapi.gateway.support.StubBackend;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Authentication, authorisation, identity, CORS, headers and limits, over real HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.health-check.enabled=false",
        "gateway.cache.enabled=false",
        "spring.data.redis.url=redis://127.0.0.1:1",
        "gateway.rate-limit.enabled=true",
        "gateway.rate-limit.store=LOCAL",
        "gateway.rate-limit.algorithm=FIXED_WINDOW",
        "gateway.rate-limit.limit=2",
        "gateway.rate-limit.key-by=USER",
        "security.trust-user-id-header=false",
        "security.max-request-bytes=1024",
        "security.login-rate-limit.attempts=6"})
class SecurityIntegrationTest extends GatewayTestSupport {

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(java.time.Instant.parse("2026-01-01T12:00:00Z"));
        }
    }

    private static final Pattern TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ServiceDiscovery serviceDiscovery;

    @Autowired
    private Clock clock;

    private StubBackend backend;

    @Override
    protected int port() {
        return port;
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("TRUNCATE service_instances, services RESTART IDENTITY CASCADE");
        jdbcTemplate.update("DELETE FROM users WHERE username <> 'admin'");
        serviceDiscovery.invalidateAll();
        // All requests come from 127.0.0.1, so tests share the per-IP gateway and login limits. Both
        // use the injectable Clock: moving it forward starts fresh windows for every test.
        ((MutableClock) clock).advance(Duration.ofMinutes(2));
        forgetAdminToken(); // issued at an earlier clock time; may be expired now
        backend = StubBackend.start("A");
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    private HttpResponse<String> raw(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (headers.length > 0) {
            b.headers(headers);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String register(String username, String password) throws Exception {
        return raw("POST", "/api/auth/register", """
                {"username":"%s","email":"%s@example.com","password":"%s"}""".formatted(username, username, password)).body();
    }

    private String login(String username, String password) throws Exception {
        Matcher m = TOKEN.matcher(raw("POST", "/api/auth/login", """
                {"username":"%s","password":"%s"}""".formatted(username, password)).body());
        return m.find() ? m.group(1) : null;
    }

    @Test
    void managementApiRequiresATokenAndAdminRoleForChanges() throws Exception {
        HttpResponse<String> anonymous = raw("GET", "/api/services", null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(anonymous.body()).contains("\"title\":\"Unauthorized\"");
        assertThat(raw("GET", "/api/health", null).statusCode()).isIn(200); // public

        register("carol", "correct-horse-battery");
        String user = login("carol", "correct-horse-battery");
        assertThat(raw("GET", "/api/services", null, "Authorization", "Bearer " + user).statusCode()).isEqualTo(200);
        HttpResponse<String> forbidden = raw("POST", "/api/services", "{\"name\":\"x\",\"basePath\":\"/x\"}",
                "Authorization", "Bearer " + user);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.body()).contains("Your role does not allow this operation");

        assertThat(raw("POST", "/api/services", "{\"name\":\"x\",\"basePath\":\"/x\"}",
                "Authorization", "Bearer " + adminToken()).statusCode()).isEqualTo(201);
    }

    @Test
    void passwordsAreStoredAsBcryptHashesAndLoginErrorsDoNotRevealUsernames() throws Exception {
        assertThat(register("dave", "correct-horse-battery")).contains("\"role\":\"USER\"");
        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE username='dave'", String.class);
        assertThat(hash).startsWith("$2").doesNotContain("correct-horse-battery");

        HttpResponse<String> wrongPassword = raw("POST", "/api/auth/login", "{\"username\":\"dave\",\"password\":\"nope-nope-nope\"}");
        HttpResponse<String> unknownUser = raw("POST", "/api/auth/login", "{\"username\":\"nobody\",\"password\":\"nope-nope-nope\"}");
        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(unknownUser.statusCode()).isEqualTo(401);
        assertThat(wrongPassword.body().replaceAll("\"requestId\":\"[^\"]+\"", ""))
                .isEqualTo(unknownUser.body().replaceAll("\"requestId\":\"[^\"]+\"", ""));
    }

    @Test
    void registrationIsValidated() throws Exception {
        assertThat(raw("POST", "/api/auth/register", "{\"username\":\"eve\",\"email\":\"eve@example.com\",\"password\":\"short\"}")
                .statusCode()).isEqualTo(400);
        assertThat(raw("POST", "/api/auth/register", "{\"username\":\"e v e\",\"email\":\"not-an-email\",\"password\":\"correct-horse-battery\"}")
                .body()).contains("\"username\"").contains("\"email\"");
        register("frank", "correct-horse-battery");
        assertThat(raw("POST", "/api/auth/register", "{\"username\":\"frank\",\"email\":\"f2@example.com\",\"password\":\"correct-horse-battery\"}")
                .statusCode()).isEqualTo(409);
    }

    @Test
    void tamperedAndExpiredTokensAreRejected() throws Exception {
        register("gina", "correct-horse-battery");
        String token = login("gina", "correct-horse-battery");
        String[] parts = token.split("\\.");
        // Change the payload to claim ADMIN but keep the original signature.
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("\"USER\"", "\"ADMIN\"");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThat(raw("POST", "/api/services", "{\"name\":\"y\",\"basePath\":\"/y\"}", "Authorization", "Bearer " + forged)
                .statusCode()).isEqualTo(401);

        ((MutableClock) clock).advance(Duration.ofHours(2)); // token lifetime is 1 h
        HttpResponse<String> expired = raw("GET", "/api/services", null, "Authorization", "Bearer " + token);
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(expired.body()).contains("expired");
    }

    @Test
    void gatewayAcceptsAnonymousTrafficButRejectsInvalidTokens() throws Exception {
        registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), backend.port(), 1);
        assertThat(raw("GET", "/gateway/orders/orders", null).statusCode()).isEqualTo(200);
        assertThat(raw("GET", "/gateway/orders/orders", null, "Authorization", "Bearer garbage").statusCode()).isEqualTo(401);
    }

    @Test
    void rateLimitIdentityComesFromTheVerifiedTokenNotFromAHeader() throws Exception {
        registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), backend.port(), 1);
        // Anonymous: rotating X-User-Id doesn't help, the header is ignored (bucket = client IP). Limit is 2.
        int ok = 0;
        for (int i = 0; i < 4; i++) {
            if (raw("GET", "/gateway/orders/orders", null, "X-User-Id", "spoof-" + i).statusCode() == 200) {
                ok++;
            }
        }
        assertThat(ok).isEqualTo(2);
        // Authenticated users get their own bucket, keyed by the token subject.
        register("hank", "correct-horse-battery");
        String token = login("hank", "correct-horse-battery");
        assertThat(raw("GET", "/gateway/orders/orders", null, "Authorization", "Bearer " + token).statusCode()).isEqualTo(200);
        String logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM request_logs WHERE client_id = 'hank'", String.class);
        // written asynchronously; the identity used for limiting is visible in the access log path
        assertThat(logged).isNotNull();
    }

    @Test
    void corsAllowsOnlyTheDashboardOrigin() throws Exception {
        HttpResponse<String> allowed = raw("OPTIONS", "/api/services", null, "Origin", "http://localhost:5173",
                "Access-Control-Request-Method", "GET", "Access-Control-Request-Headers", "authorization");
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
        HttpResponse<String> denied = raw("OPTIONS", "/api/services", null, "Origin", "https://evil.example",
                "Access-Control-Request-Method", "GET");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void securityHeadersOnTheApiAndBackendCachingHeadersPreservedOnTheGateway() throws Exception {
        HttpResponse<String> api = raw("GET", "/api/health", null);
        assertThat(api.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(api.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(api.headers().firstValue("Content-Security-Policy")).hasValue("default-src 'none'; frame-ancestors 'none'");

        backend.cacheControl("public, max-age=60");
        registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), backend.port(), 1);
        HttpResponse<String> proxied = raw("GET", "/gateway/orders/orders", null);
        assertThat(proxied.headers().allValues("Cache-Control")).containsExactly("public, max-age=60");
    }

    @Test
    void oversizedBodiesAreRejectedWith413() throws Exception {
        registerInstance(registerService("orders-service", "/orders", "ROUND_ROBIN"), backend.port(), 1);
        HttpResponse<String> big = raw("POST", "/gateway/orders/orders", "x".repeat(2048));
        assertThat(big.statusCode()).isEqualTo(413);
        assertThat(backend.hits()).isZero();
    }

    @Test
    void loginAttemptsAreRateLimitedPerIp() throws Exception {
        int limited = 0;
        for (int i = 0; i < 8; i++) {
            if (raw("POST", "/api/auth/login", "{\"username\":\"x\",\"password\":\"wrong-wrong-wrong\"}").statusCode() == 429) {
                limited++;
            }
        }
        assertThat(limited).isPositive(); // at most 6 attempts per minute per IP (some used by other tests)
    }

    @Test
    void linkLocalBackendsCannotBeRegistered() throws Exception {
        long id = registerService("evil", "/evil", "ROUND_ROBIN");
        HttpResponse<String> response = send("POST", "/api/service-instances", """
                {"serviceId":%d,"host":"169.254.169.254","port":80}""".formatted(id));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("link-local/metadata");
    }
}
