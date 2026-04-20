package dev.xssmusashi.atlas.core.pool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
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
        this(parallelism, maxInflightTasks, Thread.NORM_PRIORITY);
    }

    /**
     * @param threadPriority Thread priority for worker threads. Use
     *   {@link Thread#MIN_PRIORITY} for background work that must yield to
     *   foreground threads (e.g. game render thread, server tick thread)
     *   when CPU is contended. Use {@link Thread#NORM_PRIORITY} for
     *   benchmarks and CLI tools where Atlas owns the machine.
     */
    public DagScheduler(int parallelism, int maxInflightTasks, int threadPriority) {
        if (parallelism < 1) throw new IllegalArgumentException("parallelism must be >= 1");
        if (maxInflightTasks < 1) throw new IllegalArgumentException("maxInflightTasks must be >= 1");
        if (threadPriority < Thread.MIN_PRIORITY || threadPriority > Thread.MAX_PRIORITY) {
            throw new IllegalArgumentException("threadPriority out of range");
        }
        ForkJoinPool.ForkJoinWorkerThreadFactory factory = parent -> {
            ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(parent);
            t.setPriority(threadPriority);
            t.setName("atlas-worker-" + t.getPoolIndex());
            t.setDaemon(true);
            return t;
        };
        this.pool = new ForkJoinPool(parallelism, factory, null, false);
        this.inflightLimit = new Semaphore(maxInflightTasks);
    }

    /** Default: nCpus - 1 worker threads, max 64 in-flight tasks, NORM priority. */
    public static DagScheduler defaultPool() {
        int p = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        return new DagScheduler(p, 64);
    }

    /**
     * Background pool sized at half the cores (good headroom for the game thread)
     * with low-priority workers. Use this from in-game commands.
     */
    public static DagScheduler backgroundPool() {
        int p = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        return new DagScheduler(p, p * 4, Thread.MIN_PRIORITY);
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
