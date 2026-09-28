import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal closed-loop HTTP load generator: C worker threads, each sends its next request as soon as
 * the previous one completes, until N requests have been sent. Reports throughput, latency
 * percentiles, status codes and, if responses contain "instance":"...", which instance served each.
 *
 * Run without compiling (Java 11+ source launcher):
 *   java tests/load/LoadTest.java --url http://localhost:8080/gateway/orders/orders --requests 1000 --concurrency 20
 * Options: --warmup N (unmeasured requests first), --header Name:Value (repeatable), --timeout-ms N
 */
public class LoadTest {

    private static final Pattern INSTANCE = Pattern.compile("\"instance\":\"([^\"]+)\"");

    public static void main(String[] args) throws Exception {
        String url = null;
        int requests = 100;
        int concurrency = 10;
        int warmup = 0;
        long timeoutMs = 10_000;
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> url = args[++i];
                case "--requests" -> requests = Integer.parseInt(args[++i]);
                case "--concurrency" -> concurrency = Integer.parseInt(args[++i]);
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--timeout-ms" -> timeoutMs = Long.parseLong(args[++i]);
                case "--header" -> {
                    String[] parts = args[++i].split(":", 2);
                    headers.add(parts[0].trim());
                    headers.add(parts[1].trim());
                }
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        if (url == null) {
            throw new IllegalArgumentException("--url is required");
        }

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(Math.max(2, concurrency)))
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs)).GET();
        if (!headers.isEmpty()) {
            builder.headers(headers.toArray(String[]::new));
        }
        HttpRequest request = builder.build();

        if (warmup > 0) {
            run(client, request, warmup, concurrency);
        }
        Result result = run(client, request, requests, concurrency);
        result.print(request, requests, concurrency, warmup);
        System.exit(0);
    }

    private static Result run(HttpClient client, HttpRequest request, int total, int concurrency) throws Exception {
        long[] latenciesNanos = new long[total];
        Map<String, LongAdder> statuses = new ConcurrentHashMap<>();
        Map<String, LongAdder> instances = new ConcurrentHashMap<>();
        AtomicInteger next = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(concurrency);
        long start = System.nanoTime();
        for (int w = 0; w < concurrency; w++) {
            workers.submit(() -> {
                int i;
                while ((i = next.getAndIncrement()) < total) {
                    long t0 = System.nanoTime();
                    String status;
                    try {
                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        status = String.valueOf(response.statusCode());
                        // Only successful responses come from a backend instance. Error bodies such as
                        // RFC 9457 problem+json also have an "instance" field (the request path).
                        Matcher m = INSTANCE.matcher(response.body());
                        if (response.statusCode() / 100 == 2 && m.find()) {
                            instances.computeIfAbsent(m.group(1), k -> new LongAdder()).increment();
                        }
                    } catch (Exception e) {
                        status = "error:" + e.getClass().getSimpleName();
                    }
                    latenciesNanos[i] = System.nanoTime() - t0;
                    statuses.computeIfAbsent(status, k -> new LongAdder()).increment();
                }
            });
        }
        workers.shutdown();
        workers.awaitTermination(30, TimeUnit.MINUTES);
        return new Result(latenciesNanos, System.nanoTime() - start, statuses, instances);
    }

    private record Result(long[] latenciesNanos, long wallNanos, Map<String, LongAdder> statuses,
                          Map<String, LongAdder> instances) {

        void print(HttpRequest request, int requests, int concurrency, int warmup) {
            long[] sorted = latenciesNanos.clone();
            Arrays.sort(sorted);
            long ok = count(s -> s.startsWith("2"));
            long limited = count(s -> s.equals("429"));
            double wallSeconds = wallNanos / 1e9;
            System.out.printf("Load test: GET %s%n", request.uri());
            System.out.printf("  requests=%d concurrency=%d warmup=%d%n", requests, concurrency, warmup);
            System.out.printf("  total=%d success(2xx)=%d rateLimited(429)=%d failed(other)=%d%n",
                    requests, ok, limited, requests - ok - limited);
            System.out.printf("  wall time=%.2f s  throughput=%.1f req/s%n", wallSeconds, requests / wallSeconds);
            System.out.printf("  latency ms: avg=%.2f p50=%.2f p95=%.2f p99=%.2f max=%.2f%n",
                    Arrays.stream(sorted).average().orElse(0) / 1e6, pct(sorted, 50), pct(sorted, 95),
                    pct(sorted, 99), sorted[sorted.length - 1] / 1e6);
            System.out.printf("  status codes: %s%n", sortedCounts(statuses));
            if (!instances.isEmpty()) {
                System.out.printf("  served by: %s%n", sortedCounts(instances));
            }
        }

        private long count(java.util.function.Predicate<String> statusFilter) {
            return statuses.entrySet().stream().filter(e -> statusFilter.test(e.getKey()))
                    .mapToLong(e -> e.getValue().sum()).sum();
        }

        /** Nearest-rank percentile. */
        private static double pct(long[] sorted, int percentile) {
            int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
            return sorted[Math.max(0, rank - 1)] / 1e6;
        }

        private static Map<String, Long> sortedCounts(Map<String, LongAdder> counts) {
            Map<String, Long> result = new TreeMap<>();
            counts.forEach((k, v) -> result.put(k, v.sum()));
            return result;
        }
    }
}
