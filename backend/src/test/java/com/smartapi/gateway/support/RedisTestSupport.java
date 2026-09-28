package com.smartapi.gateway.support;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * Provides one real Redis per test JVM, from the first source available:
 * <ol>
 *   <li>{@code TEST_REDIS_URL}: an already running Redis;</li>
 *   <li>Docker: a {@code redis:7-alpine} container via Testcontainers (what CI uses);</li>
 *   <li>a {@code redis-server} binary ({@code REDIS_SERVER_BIN} or on PATH), started on a free port.</li>
 * </ol>
 * If none is available, Redis tests are SKIPPED (reported as skipped, never as passed).
 */
public final class RedisTestSupport {

    private static String url;
    private static boolean resolved;

    private RedisTestSupport() {
    }

    public static synchronized Optional<String> redisUrl() {
        if (!resolved) {
            resolved = true;
            url = resolve();
        }
        return Optional.ofNullable(url);
    }

    private static String resolve() {
        String configured = System.getenv("TEST_REDIS_URL");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (dockerAvailable()) {
            GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
            redis.start(); // stopped by Testcontainers' Ryuk when the JVM exits
            return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
        }
        return localBinary().map(RedisTestSupport::startLocal).orElse(null);
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    private static Optional<Path> localBinary() {
        String explicit = System.getenv("REDIS_SERVER_BIN");
        if (explicit != null && Files.isExecutable(Path.of(explicit))) {
            return Optional.of(Path.of(explicit));
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
            Path candidate = Path.of(dir, "redis-server");
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static String startLocal(Path binary) {
        try {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            Process process = new ProcessBuilder(binary.toString(), "--port", String.valueOf(port),
                    "--bind", "127.0.0.1", "--save", "", "--appendonly", "no")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
            for (int i = 0; i < 100; i++) {
                try (Socket ignored = new Socket("127.0.0.1", port)) {
                    return "redis://127.0.0.1:" + port;
                } catch (IOException notYet) {
                    TimeUnit.MILLISECONDS.sleep(50);
                }
            }
            process.destroy();
            return null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }
}
