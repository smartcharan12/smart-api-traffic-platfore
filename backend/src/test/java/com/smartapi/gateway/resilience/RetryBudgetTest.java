package com.smartapi.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryBudgetTest {

    @Test
    void retriesAreLimitedToTheConfiguredRatioOfRequests() {
        RetryBudget budget = new RetryBudget(0.2, 100, 0);
        int retries = 0;
        for (int i = 0; i < 1000; i++) {      // an outage: every request fails and wants to retry
            budget.recordRequest();
            if (budget.tryWithdraw()) {
                retries++;
            }
        }
        assertThat(retries).isEqualTo(200);  // 20% of 1000, not 1000
    }

    @Test
    void savedBudgetIsCapped() {
        RetryBudget budget = new RetryBudget(0.2, 10, 0);
        for (int i = 0; i < 10_000; i++) {
            budget.recordRequest();
        }
        assertThat(budget.available()).isEqualTo(10.0);
    }

    @Test
    void concurrentWithdrawalsNeverOverspend() throws Exception {
        RetryBudget budget = new RetryBudget(0.2, 100, 50);
        AtomicInteger granted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                futures.add(pool.submit(() -> {
                    if (budget.tryWithdraw()) {
                        granted.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(granted.get()).isEqualTo(50);
        assertThat(budget.available()).isZero();
    }
}
