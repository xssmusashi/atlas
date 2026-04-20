package dev.xssmusashi.atlas.core.pool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Bounded work-stealing scheduler for tile-task graphs.
 * <p>
 * Wraps a {@link ForkJoinPool} sized to {@code parallelism}, with a {@link Semaphore}
 * limiting in-flight tasks (back-pressure). Submitting beyond the limit blocks until
 * a slot frees, preventing OOM on teleport spam.
 */
public final class DagScheduler implements AutoCloseable {

    private final ForkJoinPool pool;
    private final Semaphore inflightLimit;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();

    public DagScheduler(int parallelism, int maxInflightTasks) {
        if (parallelism < 1) throw new IllegalArgumentException("parallelism must be >= 1");
        if (maxInflightTasks < 1) throw new IllegalArgumentException("maxInflightTasks must be >= 1");
        this.pool = new ForkJoinPool(parallelism);
        this.inflightLimit = new Semaphore(maxInflightTasks);
    }

    /** Default: nCpus - 1 worker threads, max 64 in-flight tasks. */
    public static DagScheduler defaultPool() {
        int p = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        return new DagScheduler(p, 64);
    }

    public int parallelism() {
        return pool.getParallelism();
    }

    public long submittedCount() { return submitted.get(); }
    public long completedCount() { return completed.get(); }

    /**
     * Submit a task. Blocks if {@code maxInflightTasks} are already in flight.
     * The returned future completes (success or failure) on a pool thread; chained
     * stages run via {@link CompletableFuture#thenApplyAsync}.
     */
    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        try {
            inflightLimit.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
        submitted.incrementAndGet();
        return CompletableFuture.supplyAsync(task, pool)
            .whenComplete((r, ex) -> {
                completed.incrementAndGet();
                inflightLimit.release();
            });
    }

    /** Shutdown — waits for currently running tasks but does not start queued ones. */
    @Override
    public void close() {
        pool.shutdown();
    }
}
