import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LostUpdate {
    static int plain;
    static final AtomicInteger atomic = new AtomicInteger();
    static int locked;
    static final Object lock = new Object();

    public static void main(String[] args) throws Exception {
        int threads = 8, perThread = 1_000_000;
        for (String mode : new String[]{"plain int ++", "AtomicInteger", "synchronized"}) {
            plain = 0; atomic.set(0); locked = 0;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            long t0 = System.nanoTime();
            for (int t = 0; t < threads; t++) pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    switch (mode) {
                        case "plain int ++" -> plain++;
                        case "AtomicInteger" -> atomic.incrementAndGet();
                        default -> { synchronized (lock) { locked++; } }
                    }
                }
            });
            pool.shutdown(); pool.awaitTermination(1, TimeUnit.MINUTES);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int result = switch (mode) { case "plain int ++" -> plain; case "AtomicInteger" -> atomic.get(); default -> locked; };
            System.out.printf("%-14s expected=%,d actual=%,d lost=%,d time=%d ms%n", mode, threads * perThread, result, threads * perThread - result, ms);
        }
    }
}
