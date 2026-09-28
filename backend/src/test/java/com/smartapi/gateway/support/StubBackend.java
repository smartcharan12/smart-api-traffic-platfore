package com.smartapi.gateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP server (JDK built-in) standing in for a backend instance in gateway tests. It echoes
 * what it received as JSON so tests can assert on what the gateway actually forwarded.
 */
public final class StubBackend implements AutoCloseable {

    public enum Mode { OK, ERROR, SLOW }

    private final String name;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(64);
    private final AtomicInteger hits = new AtomicInteger();
    private volatile Mode mode = Mode.OK;
    private volatile long delayMs;
    private volatile String lastRequestId;

    private StubBackend(String name) throws IOException {
        this.name = name;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public static StubBackend start(String name) throws IOException {
        return new StubBackend(name);
    }

    /** A port on which nothing listens, so connecting to it is refused. */
    public static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        lastRequestId = exchange.getRequestHeaders().getFirst("X-Request-ID");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (mode == Mode.SLOW) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String json = """
                {"instance":"%s","method":"%s","path":"%s","query":"%s","body":"%s","forwardedFor":"%s"}"""
                .formatted(name, exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                        exchange.getRequestURI().getRawQuery(), body.replace("\"", "'"),
                        exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("X-Backend", name);
        exchange.sendResponseHeaders(mode == Mode.ERROR ? 500 : 200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    public void mode(Mode mode, long delayMs) {
        this.mode = mode;
        this.delayMs = delayMs;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public int hits() {
        return hits.get();
    }

    public String lastRequestId() {
        return lastRequestId;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
