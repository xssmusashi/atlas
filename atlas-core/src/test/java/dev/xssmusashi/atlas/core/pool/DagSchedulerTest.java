package dev.xssmusashi.atlas.core.pool;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DagSchedulerTest {

    @Test
    void submit_runsTaskAndReturnsResult() throws Exception {
        try (DagScheduler s = new DagScheduler(2, 4)) {
            int result = s.submit(() -> 42).get(5, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(42);
            assertThat(s.submittedCount()).isEqualTo(1);
            assertThat(s.completedCount()).isEqualTo(1);
        }
    }

    @Test
    void submit_runsManyTasksInParallel() throws Exception {
        try (DagScheduler s = new DagScheduler(4, 32)) {
            int n = 50;
            AtomicInteger sum = new AtomicInteger();
            CompletableFuture<?>[] futs = new CompletableFuture<?>[n];
            for (int i = 0; i < n; i++) {
                final int v = i;
                futs[i] = s.submit(() -> { sum.addAndGet(v); return v; });
            }
            CompletableFuture.allOf(futs).get(10, TimeUnit.SECONDS);
            assertThat(sum.get()).isEqualTo(n * (n - 1) / 2);
            assertThat(s.completedCount()).isEqualTo(n);
        }
    }

    @Test
    void submit_blocksOnceInflightLimitReached() {
        // Capacity of 2 in-flight, 3rd submit must block until one completes.
        try (DagScheduler s = new DagScheduler(2, 2)) {
            AtomicInteger started = new AtomicInteger();
            CompletableFuture<Integer> slow1 = s.submit(() -> {
                started.incrementAndGet();
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return 1;
            });
            CompletableFuture<Integer> slow2 = s.submit(() -> {
                started.incrementAndGet();
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return 2;
            });
            // Third submit must wait for one of the slows.
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
                CompletableFuture<Integer> third = s.submit(() -> 3);
                assertThat(third.join()).isEqualTo(3);
            });
            slow1.join();
            slow2.join();
            assertThat(started.get()).isEqualTo(2);
        }
    }

    @Test
    void parallelism_reflectsConfiguredValue() {
        try (DagScheduler s = new DagScheduler(7, 16)) {
            assertThat(s.parallelism()).isEqualTo(7);
        }
    }
}
